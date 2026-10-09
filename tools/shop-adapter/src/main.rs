use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use serde::Deserialize;
use serde_json::{json, Value};
use thiserror::Error;

const TAIL_CHARS: usize = 4000;
const HEALTH_WAIT: Duration = Duration::from_secs(60);
const HTTP_TIMEOUT: Duration = Duration::from_secs(2);
const STOP_WAIT: Duration = Duration::from_secs(30);
const STOCK_RESET: &str = "DELETE FROM customer_order_line; DELETE FROM customer_order; DELETE FROM cart_line; DELETE FROM cart; UPDATE product SET stock = CASE sku WHEN 'MUG-STONE' THEN 1000 WHEN 'TEE-CREW-M' THEN 250 WHEN 'NB-DOT-A5' THEN 500 WHEN 'LAMP-DESK' THEN 2 ELSE stock END; SELECT setval(pg_get_serial_sequence('customer_order', 'id'), 1, false)";

#[derive(Debug, Error)]
enum AdapterError {
    #[error("{0}")]
    Message(String),
    #[error(transparent)]
    Io(#[from] io::Error),
}

#[derive(Debug, Deserialize)]
struct Request {
    phase: String,
    root: PathBuf,
    output: PathBuf,
    #[serde(default = "enabled")]
    coverage: bool,
    #[serde(default)]
    env: BTreeMap<String, String>,
}

fn enabled() -> bool {
    true
}

struct TestRecord {
    name: String,
    status: String,
    duration_ms: u64,
    message: Option<String>,
    attachments: Vec<Value>,
}

struct Run {
    request: Request,
    file_env: BTreeMap<String, String>,
    profile: PathBuf,
    profile_pattern: String,
    binary: PathBuf,
    processes: Vec<Child>,
    tests: Vec<TestRecord>,
    coverage: Vec<PathBuf>,
    diagnostics: Vec<String>,
    ok: bool,
    api: String,
    database_url: String,
}

fn main() {
    let mut args = std::env::args().skip(1);
    if args.next().as_deref() != Some("run") {
        let _ = writeln!(io::stderr(), "usage: shop-adapter run");
        std::process::exit(2);
    }
    let request = match serde_json::from_reader(io::stdin()) {
        Ok(request) => request,
        Err(error) => finish(failure(&error.to_string())),
    };
    match Run::new(request).execute() {
        Ok(response) => finish(response),
        Err(error) => finish(failure(&error.to_string())),
    }
}

fn finish(response: Value) -> ! {
    let ok = response
        .get("success")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    if writeln!(io::stdout(), "{response}").is_err() {
        std::process::exit(1);
    }
    std::process::exit(if ok { 0 } else { 1 });
}

fn failure(message: &str) -> Value {
    json!({
        "protocol": 1,
        "success": false,
        "tests": [],
        "coverage": [],
        "diagnostics": [message],
    })
}

impl Run {
    fn new(request: Request) -> Self {
        let profile = request.output.join("profraw");
        let binary = request.root.join("target/instrumented/debug/shop-rs-api");
        let file_env = read_env_file(&request.root.join(".env"));
        let profile_pattern = profile
            .join("shop-%p.profraw")
            .to_string_lossy()
            .into_owned();
        Self {
            request,
            file_env,
            profile,
            profile_pattern,
            binary,
            processes: Vec::new(),
            tests: Vec::new(),
            coverage: Vec::new(),
            diagnostics: Vec::new(),
            ok: true,
            api: String::new(),
            database_url: String::new(),
        }
    }

    fn execute(mut self) -> Result<Value, AdapterError> {
        fs::create_dir_all(&self.request.output)?;
        fs::create_dir_all(&self.profile)?;
        if !matches!(
            self.request.phase.as_str(),
            "integration" | "functional" | "visual"
        ) {
            return Err(AdapterError::Message(format!(
                "unsupported phase {}",
                self.request.phase
            )));
        }
        self.database()?;
        self.build()?;
        self.probe_missing_database()?;
        self.probe_bind()?;
        let phase = self.request.phase.clone();
        let phased = (|| {
            self.start_api()?;
            if phase == "integration" {
                self.cargo_tests()
            } else {
                self.reset_stock()?;
                self.playwright()
            }
        })();
        self.stop_children();
        phased?;
        if self.request.coverage && self.request.phase == "integration" {
            if let Err(error) = self.export_server() {
                self.ok = false;
                self.diagnostics.push(error.to_string());
            }
        }
        Ok(self.response())
    }

    fn response(&mut self) -> Value {
        if self.tests.iter().any(|test| test.status == "failed") {
            self.ok = false;
        }
        json!({
            "protocol": 1,
            "success": self.ok,
            "tests": self.tests.iter().map(test_json).collect::<Vec<_>>(),
            "coverage": self.coverage,
            "diagnostics": self.diagnostics,
        })
    }

    fn setting(&self, key: &str) -> Result<String, AdapterError> {
        present(std::env::var(key).ok())
            .or_else(|| present(self.file_env.get(key).cloned()))
            .or_else(|| present(self.request.env.get(key).cloned()))
            .ok_or_else(|| AdapterError::Message(format!("{key} is not set")))
    }

    fn database(&mut self) -> Result<(), AdapterError> {
        let host =
            request_or_process(&self.request.env, "CHERRY_TEST_SHOP_DB_HOST").ok_or_else(|| {
                AdapterError::Message(
                    "Postgres endpoint is missing from the adapter request".into(),
                )
            })?;
        let port =
            request_or_process(&self.request.env, "CHERRY_TEST_SHOP_DB_PORT").ok_or_else(|| {
                AdapterError::Message(
                    "Postgres endpoint is missing from the adapter request".into(),
                )
            })?;
        let user = percent_encode(&self.setting("SHOP_DB_USER")?);
        let password = percent_encode(&self.setting("SHOP_DB_PASSWORD")?);
        let name = percent_encode(&self.setting("SHOP_DB_NAME")?);
        self.database_url = format!("postgres://{user}:{password}@{host}:{port}/{name}");
        Ok(())
    }

    fn build(&self) -> Result<(), AdapterError> {
        let log = self.request.output.join("build.log");
        let mut command = Command::new("cargo");
        command
            .args(["build", "-p", "shop-rs-api", "--bin", "shop-rs-api"])
            .current_dir(&self.request.root)
            .env(
                "CARGO_TARGET_DIR",
                self.request.root.join("target/instrumented"),
            )
            .env("RUSTFLAGS", "-Cinstrument-coverage")
            .env_remove("LLVM_PROFILE_FILE");
        let code = command_status(command, &log, None)?;
        if code != 0 || !self.binary.is_file() {
            return Err(AdapterError::Message(format!(
                "instrumented build failed\n{}",
                tail(&log, TAIL_CHARS)
            )));
        }
        Ok(())
    }

    fn server_command(&self) -> Command {
        let mut command = Command::new(&self.binary);
        command
            .current_dir(&self.request.root)
            .env("LLVM_PROFILE_FILE", &self.profile_pattern)
            .env("RUST_LOG", "shop_rs_api=info")
            .env_remove("RUSTFLAGS");
        command
    }

    fn probe_missing_database(&mut self) -> Result<(), AdapterError> {
        let log = self.request.output.join("probe-missing-database.stderr");
        let mut command = self.server_command();
        command
            .env_remove("DATABASE_URL")
            .env_remove("SHOP_API_ADDR");
        let code = command_status(command, &log, Some(Duration::from_secs(30)))?;
        if code != 1 {
            self.ok = false;
            self.diagnostics.push(format!(
                "missing-database probe exited {code}\n{}",
                tail(&log, TAIL_CHARS)
            ));
        }
        Ok(())
    }

    fn probe_bind(&mut self) -> Result<(), AdapterError> {
        let listener = TcpListener::bind(("127.0.0.1", 0))?;
        let port = listener.local_addr()?.port();
        let log = self.request.output.join("probe-bind.stderr");
        let mut command = self.server_command();
        command
            .env("DATABASE_URL", &self.database_url)
            .env("SHOP_API_ADDR", format!("127.0.0.1:{port}"));
        let code = command_status(command, &log, Some(Duration::from_secs(60)))?;
        drop(listener);
        if code != 1 {
            self.ok = false;
            self.diagnostics.push(format!(
                "bind probe exited {code}\n{}",
                tail(&log, TAIL_CHARS)
            ));
        }
        Ok(())
    }

    fn start_api(&mut self) -> Result<(), AdapterError> {
        let listener = TcpListener::bind(("127.0.0.1", 0))?;
        let port = listener.local_addr()?.port();
        drop(listener);
        let log = self.request.output.join("api.stderr");
        let mut command = self.server_command();
        command
            .env("DATABASE_URL", &self.database_url)
            .env("SHOP_API_ADDR", format!("127.0.0.1:{port}"));
        self.processes.push(spawn_logged(command, &log)?);
        self.api = format!("http://127.0.0.1:{port}");
        wait_http(&format!("{}/health", self.api), &log)
    }

    fn cargo_tests(&mut self) -> Result<(), AdapterError> {
        let log = self.request.output.join("tests.stdout");
        let manifest = self.request.root.join("Cargo.toml");
        let mut command = Command::new("cargo");
        command
            .args(["test", "--manifest-path"])
            .arg(&manifest)
            .args([
                "-p",
                "shop-rs-api",
                "--test",
                "integration",
                "--",
                "--ignored",
                "--test-threads=1",
                "--color",
                "never",
            ])
            .current_dir(&self.request.root)
            .env_remove("RUSTFLAGS")
            .env_remove("LLVM_PROFILE_FILE")
            .env("CHERRY_TEST_SKIP_DOCKER", "1")
            .env("CHERRY_TEST_SHOP_API_URL", &self.api)
            .env("CHERRY_TEST_SHOP_API_HOST", "127.0.0.1")
            .env("CHERRY_TEST_SHOP_API_PORT", port_of(&self.api));
        let code = command_status(command, &log, None)?;
        let text = fs::read(&log)
            .map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
            .unwrap_or_default();
        for line in text.lines() {
            if let Some((name, status)) = parse_cargo_test_line(line) {
                self.tests.push(TestRecord {
                    name,
                    status,
                    duration_ms: 0,
                    message: None,
                    attachments: Vec::new(),
                });
            }
        }
        if code != 0 {
            self.ok = false;
            self.diagnostics.push(format!(
                "integration tests exited {code}\n{}",
                tail(&log, TAIL_CHARS)
            ));
        }
        if self.tests.is_empty() {
            self.ok = false;
            self.diagnostics
                .push("no integration test results were parsed".into());
        }
        Ok(())
    }

    fn reset_stock(&self) -> Result<(), AdapterError> {
        let project = compose_project(&self.request.env);
        let user = self.setting("SHOP_DB_USER")?;
        let name = self.setting("SHOP_DB_NAME")?;
        let output = Command::new("docker")
            .args([
                "compose",
                "-p",
                &project,
                "-f",
                "docker-compose.test.yml",
                "exec",
                "-T",
                "shop-db",
                "psql",
                "-U",
                &user,
                "-d",
                &name,
                "-v",
                "ON_ERROR_STOP=1",
                "-c",
                STOCK_RESET,
            ])
            .current_dir(&self.request.root)
            .output()?;
        if output.status.success() {
            return Ok(());
        }
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        let detail = if !stderr.trim().is_empty() {
            end_slice(&stderr, 2000)
        } else {
            end_slice(&stdout, 2000)
        };
        Err(AdapterError::Message(if detail.is_empty() {
            "stock reset failed".into()
        } else {
            detail
        }))
    }

    fn playwright(&mut self) -> Result<(), AdapterError> {
        let web = self.request.root.join("shop-ts-web-react");
        let listener = TcpListener::bind(("127.0.0.1", 0))?;
        let port = listener.local_addr()?.port();
        drop(listener);
        let log = self.request.output.join("vite.stderr");
        let mut vite = Command::new("pnpm");
        vite.args(["exec", "vite", "--host", "127.0.0.1"])
            .current_dir(&web)
            .env("SHOP_API_URL", &self.api)
            .env("SHOP_WEB_PORT", port.to_string())
            .env("SHOP_ISTANBUL", "1");
        self.processes.push(spawn_logged(vite, &log)?);
        let web_url = format!("http://127.0.0.1:{port}");
        wait_http(&web_url, &log)?;
        let install = Command::new("pnpm")
            .args(["exec", "playwright", "install", "chromium"])
            .current_dir(&web)
            .output()?;
        if !install.status.success() {
            let stderr = String::from_utf8_lossy(&install.stderr);
            return Err(AdapterError::Message(if stderr.trim().is_empty() {
                "playwright browser install failed".into()
            } else {
                end_slice(&stderr, 2000)
            }));
        }
        let coverage_file = self.request.output.join("coverage.jsonl");
        let report = self.request.output.join("playwright.json");
        let mut args = vec![
            "exec".into(),
            "playwright".into(),
            "test".into(),
            format!("tests/{}.spec.ts", self.request.phase),
            "--reporter=json".into(),
        ];
        if std::env::var("CHERRY_TEST_UPDATE_SNAPSHOTS").ok().as_deref() == Some("1") {
            args.push("--update-snapshots".into());
        }
        let stdout = self.request.output.join("tests.stdout");
        let mut test = Command::new("pnpm");
        test.args(&args)
            .current_dir(&web)
            .env("SHOP_WEB_URL", &web_url)
            .env("SHOP_COVERAGE_FILE", &coverage_file)
            .env("SHOP_ROOT", &self.request.root)
            .env("SHOP_DB_USER", self.setting("SHOP_DB_USER")?)
            .env("SHOP_DB_NAME", self.setting("SHOP_DB_NAME")?)
            .env("SHOP_COMPOSE_PROJECT", compose_project(&self.request.env))
            .env("PLAYWRIGHT_JSON_OUTPUT_FILE", &report)
            .env("CI", "1");
        let code = command_status(test, &stdout, None)?;
        if !report.is_file() {
            return Err(AdapterError::Message(format!(
                "playwright wrote no JSON report\n{}",
                tail(&stdout, TAIL_CHARS)
            )));
        }
        let payload: Value = serde_json::from_slice(&fs::read(&report)?)
            .map_err(|error| AdapterError::Message(error.to_string()))?;
        self.collect_playwright(
            payload
                .get("suites")
                .cloned()
                .unwrap_or_else(|| Value::Array(Vec::new())),
        );
        match self.browser_lcov(&web, &coverage_file)? {
            Some(path) => self.coverage.push(path),
            None if self.request.coverage => {
                self.ok = false;
                self.diagnostics.push("browser coverage was empty".into());
            }
            None => {}
        }
        if code != 0 {
            self.ok = false;
            self.diagnostics.push(format!(
                "playwright exited {code}\n{}",
                tail(&stdout, TAIL_CHARS)
            ));
        }
        if self.tests.is_empty() {
            self.ok = false;
            self.diagnostics.push("playwright reported no tests".into());
        }
        Ok(())
    }

    fn collect_playwright(&mut self, suites: Value) {
        let Value::Array(suites) = suites else {
            return;
        };
        for suite in suites {
            self.collect_playwright(
                suite
                    .get("suites")
                    .cloned()
                    .unwrap_or_else(|| Value::Array(Vec::new())),
            );
            let Some(specs) = suite.get("specs").and_then(Value::as_array) else {
                continue;
            };
            for spec in specs {
                let mut results = Vec::new();
                if let Some(cases) = spec.get("tests").and_then(Value::as_array) {
                    for case in cases {
                        if let Some(found) = case.get("results").and_then(Value::as_array) {
                            results.extend(found.iter().cloned());
                        }
                    }
                }
                let result = results.last().cloned().unwrap_or_else(|| json!({}));
                let status = match result.get("status").and_then(Value::as_str) {
                    Some("passed") => "passed",
                    Some("skipped") => "skipped",
                    _ => "failed",
                };
                let mut attachments = Vec::new();
                if let Some(found) = result.get("attachments").and_then(Value::as_array) {
                    for (index, attachment) in found.iter().enumerate() {
                        let Some(source) = attachment.get("path").and_then(Value::as_str) else {
                            continue;
                        };
                        let source = PathBuf::from(source);
                        if !source.is_file() {
                            continue;
                        }
                        let file_name = source
                            .file_name()
                            .map(|name| name.to_string_lossy().into_owned())
                            .unwrap_or_else(|| "attachment".into());
                        let dest = self
                            .request
                            .output
                            .join("attachments")
                            .join(format!("{}-{index}-{file_name}", self.tests.len()));
                        if dest.parent().map(fs::create_dir_all).transpose().is_err()
                            || fs::copy(&source, &dest).is_err()
                        {
                            continue;
                        }
                        let dest_name = dest
                            .file_name()
                            .map(|name| name.to_string_lossy().into_owned())
                            .unwrap_or(file_name);
                        let name = attachment
                            .get("name")
                            .and_then(Value::as_str)
                            .filter(|name| !name.is_empty())
                            .unwrap_or(dest_name.as_str());
                        let media_type = attachment
                            .get("contentType")
                            .and_then(Value::as_str)
                            .filter(|value| !value.is_empty())
                            .unwrap_or("application/octet-stream");
                        attachments
                            .push(json!({"name": name, "path": dest, "media_type": media_type}));
                    }
                }
                let title = spec
                    .get("title")
                    .and_then(Value::as_str)
                    .filter(|title| !title.is_empty())
                    .unwrap_or("unnamed");
                let message = result
                    .pointer("/error/message")
                    .and_then(Value::as_str)
                    .map(|message| prefix(message, 4000))
                    .filter(|message| !message.is_empty());
                self.tests.push(TestRecord {
                    name: title.to_string(),
                    status: status.to_string(),
                    duration_ms: whole_millis(result.get("duration").unwrap_or(&Value::Null)),
                    message,
                    attachments,
                });
            }
        }
    }

    fn browser_lcov(
        &mut self,
        web: &Path,
        coverage_file: &Path,
    ) -> Result<Option<PathBuf>, AdapterError> {
        if !coverage_file.is_file()
            || fs::metadata(coverage_file)
                .map(|meta| meta.len())
                .unwrap_or(0)
                == 0
        {
            return Ok(None);
        }
        let directory = self.request.output.join("browser");
        let output = Command::new("node")
            .arg(web.join("scripts/write-lcov.mjs"))
            .arg(coverage_file)
            .arg(&directory)
            .current_dir(web)
            .output()?;
        let lcov = directory.join("browser.lcov");
        if !output.status.success() || !lcov.is_file() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            self.ok = false;
            self.diagnostics.push(if stderr.trim().is_empty() {
                "browser LCOV was not written".into()
            } else {
                end_slice(&stderr, 2000)
            });
            return Ok(None);
        }
        Ok(Some(lcov))
    }

    fn export_server(&mut self) -> Result<(), AdapterError> {
        let profraw = fs::read_dir(&self.profile)?
            .filter_map(|entry| entry.ok())
            .map(|entry| entry.path())
            .filter(|path| path.extension().and_then(|ext| ext.to_str()) == Some("profraw"))
            .collect::<Vec<_>>();
        if profraw.is_empty() {
            return Err(AdapterError::Message(
                "instrumented server wrote no profraw profiles".into(),
            ));
        }
        let (profdata_tool, cov_tool) = llvm_tools()?;
        let profdata = self.request.output.join("shop.profdata");
        let merged = Command::new(profdata_tool)
            .arg("merge")
            .arg("-sparse")
            .arg("-o")
            .arg(&profdata)
            .args(&profraw)
            .output()?;
        if !merged.status.success() {
            return Err(AdapterError::Message(end_slice(
                &String::from_utf8_lossy(&merged.stderr),
                2000,
            )));
        }
        let exported = Command::new(cov_tool)
            .arg("export")
            .arg(&self.binary)
            .arg(format!("-instr-profile={}", profdata.display()))
            .arg("-format=lcov")
            .output()?;
        if !exported.status.success() {
            let stderr = String::from_utf8_lossy(&exported.stderr);
            return Err(AdapterError::Message(if stderr.trim().is_empty() {
                "llvm-cov export failed".into()
            } else {
                end_slice(&stderr, 2000)
            }));
        }
        let (filtered, sample) = filter_lcov(&String::from_utf8_lossy(&exported.stdout));
        if !filtered.contains("DA:") {
            return Err(AdapterError::Message(format!(
                "server coverage had no shop-rs-api lines (sample source: {sample})"
            )));
        }
        let destination = self.request.output.join("server-full.lcov");
        fs::write(&destination, filtered)?;
        self.coverage.push(destination);
        Ok(())
    }

    fn stop_children(&mut self) {
        for child in &mut self.processes {
            if child.try_wait().ok().flatten().is_none() {
                let _ = Command::new("kill")
                    .args(["-TERM", &child.id().to_string()])
                    .status();
            }
        }
        for child in &mut self.processes {
            if child.try_wait().ok().flatten().is_some() {
                continue;
            }
            let started = Instant::now();
            while started.elapsed() < STOP_WAIT {
                if child.try_wait().ok().flatten().is_some() {
                    break;
                }
                thread::sleep(Duration::from_millis(50));
            }
            if child.try_wait().ok().flatten().is_some() {
                continue;
            }
            let _ = child.kill();
            let _ = child.wait();
            self.ok = false;
            self.diagnostics
                .push("a child required SIGKILL; coverage may be incomplete".into());
        }
        self.processes.clear();
    }
}

fn test_json(test: &TestRecord) -> Value {
    let mut value = json!({
        "name": test.name,
        "status": test.status,
        "duration_ms": test.duration_ms,
        "attachments": test.attachments,
    });
    if let Some(message) = &test.message {
        value["message"] = Value::String(message.clone());
    }
    value
}

fn present(value: Option<String>) -> Option<String> {
    value.filter(|item| !item.is_empty())
}

fn request_or_process(env: &BTreeMap<String, String>, key: &str) -> Option<String> {
    present(env.get(key).cloned()).or_else(|| present(std::env::var(key).ok()))
}

fn compose_project(env: &BTreeMap<String, String>) -> String {
    present(std::env::var("CHERRY_TEST_COMPOSE_PROJECT").ok())
        .or_else(|| present(env.get("CHERRY_TEST_COMPOSE_PROJECT").cloned()))
        .unwrap_or_else(|| "cherry-test".into())
}

fn port_of(url: &str) -> String {
    url.rsplit_once(':')
        .map(|(_, port)| port.to_string())
        .unwrap_or_default()
}

fn command_status(
    command: Command,
    log: &Path,
    timeout: Option<Duration>,
) -> Result<i32, AdapterError> {
    let mut child = spawn_logged(command, log)?;
    match timeout {
        None => Ok(code_of(child.wait()?)),
        Some(limit) => wait_limited(&mut child, limit, log),
    }
}

fn spawn_logged(mut command: Command, log: &Path) -> Result<Child, AdapterError> {
    if let Some(parent) = log.parent() {
        fs::create_dir_all(parent)?;
    }
    let file = File::create(log)?;
    command
        .stdout(Stdio::from(file.try_clone()?))
        .stderr(Stdio::from(file));
    Ok(command.spawn()?)
}

fn wait_limited(child: &mut Child, limit: Duration, log: &Path) -> Result<i32, AdapterError> {
    let started = Instant::now();
    loop {
        if let Some(status) = child.try_wait()? {
            return Ok(code_of(status));
        }
        if started.elapsed() >= limit {
            let _ = child.kill();
            let _ = child.wait();
            return Err(AdapterError::Message(format!(
                "process timed out after {}s\n{}",
                limit.as_secs(),
                tail(log, TAIL_CHARS)
            )));
        }
        thread::sleep(Duration::from_millis(50));
    }
}

fn code_of(status: ExitStatus) -> i32 {
    status.code().unwrap_or(1)
}

fn wait_http(url: &str, log: &Path) -> Result<(), AdapterError> {
    let deadline = Instant::now() + HEALTH_WAIT;
    while Instant::now() < deadline {
        if http_ok(url) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(200));
    }
    Err(AdapterError::Message(format!(
        "{url} did not become ready\n{}",
        tail(log, TAIL_CHARS)
    )))
}

fn http_ok(url: &str) -> bool {
    let Some(rest) = url.strip_prefix("http://") else {
        return false;
    };
    let (authority, path) = match rest.split_once('/') {
        Some((authority, path)) => (authority, format!("/{path}")),
        None => (rest, "/".into()),
    };
    let Some((host, port)) = authority.rsplit_once(':') else {
        return false;
    };
    let (Ok(ip), Ok(port)) = (host.parse::<IpAddr>(), port.parse::<u16>()) else {
        return false;
    };
    let Ok(mut stream) = TcpStream::connect_timeout(&SocketAddr::new(ip, port), HTTP_TIMEOUT)
    else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(HTTP_TIMEOUT));
    let _ = stream.set_write_timeout(Some(HTTP_TIMEOUT));
    if stream
        .write_all(
            format!("GET {path} HTTP/1.1\r\nHost: {authority}\r\nConnection: close\r\n\r\n")
                .as_bytes(),
        )
        .is_err()
    {
        return false;
    }
    let mut buffer = Vec::new();
    let _ = stream.read_to_end(&mut buffer);
    let header = String::from_utf8_lossy(&buffer);
    header
        .split("\r\n")
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        == Some("200")
}

fn llvm_tools() -> Result<(PathBuf, PathBuf), AdapterError> {
    let sysroot = checked_output(Command::new("rustc").arg("--print").arg("sysroot"))?;
    let verbose = checked_output(Command::new("rustc").arg("-vV"))?;
    let host = verbose
        .lines()
        .find_map(|line| line.strip_prefix("host:"))
        .map(str::trim)
        .filter(|host| !host.is_empty())
        .ok_or_else(|| AdapterError::Message("rustc host triple is missing".into()))?;
    let bindir = PathBuf::from(sysroot.trim())
        .join("lib/rustlib")
        .join(host)
        .join("bin");
    Ok((bindir.join("llvm-profdata"), bindir.join("llvm-cov")))
}

fn checked_output(command: &mut Command) -> Result<String, AdapterError> {
    let output = command.output()?;
    if !output.status.success() {
        return Err(AdapterError::Message(end_slice(
            &String::from_utf8_lossy(&output.stderr),
            2000,
        )));
    }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

fn keep_server_source(path: &str) -> bool {
    let normalized = path.replace('\\', "/");
    normalized.contains("/shop-rs-api/src/") && normalized.ends_with(".rs")
}

fn filter_lcov(text: &str) -> (String, String) {
    let mut kept = String::new();
    let mut record = String::new();
    let mut include = false;
    let mut sample = String::new();
    for line in text.split_inclusive('\n') {
        if let Some(source) = line.strip_prefix("SF:") {
            record = line.to_string();
            let source = source.trim();
            if sample.is_empty() {
                sample = source.to_string();
            }
            include = keep_server_source(source);
        } else if line.starts_with("end_of_record") {
            record.push_str(line);
            if include {
                kept.push_str(&record);
            }
            record.clear();
            include = false;
        } else if include && !line.starts_with("DA:0,") {
            record.push_str(line);
        }
    }
    (kept, sample)
}

fn parse_cargo_test_line(line: &str) -> Option<(String, String)> {
    let rest = line.trim().strip_prefix("test ")?;
    let (name, status) = rest.split_once(" ... ")?;
    if name.is_empty() || name.chars().any(char::is_whitespace) {
        return None;
    }
    let status = match status {
        "ok" => "passed",
        "FAILED" => "failed",
        "ignored" => "skipped",
        _ => return None,
    };
    Some((name.to_string(), status.to_string()))
}

fn read_env_file(path: &Path) -> BTreeMap<String, String> {
    let mut values = BTreeMap::new();
    let Ok(text) = fs::read_to_string(path) else {
        return values;
    };
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        values.insert(
            key.trim().to_string(),
            value
                .trim()
                .trim_matches(|ch| ch == '"' || ch == '\'')
                .to_string(),
        );
    }
    values
}

fn percent_encode(value: &str) -> String {
    let mut encoded = String::new();
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~') {
            encoded.push(char::from(byte));
        } else {
            encoded.push_str(&format!("%{byte:02X}"));
        }
    }
    encoded
}

fn whole_millis(value: &Value) -> u64 {
    if let Some(number) = value.as_u64() {
        return number;
    }
    let Some(number) = value.as_f64() else {
        return 0;
    };
    if !number.is_finite() || number <= 0.0 || number >= 1.0e15 {
        return 0;
    }
    format!("{:.0}", number.trunc()).parse().unwrap_or(0)
}

fn prefix(value: &str, limit: usize) -> String {
    if value.chars().count() <= limit {
        value.to_string()
    } else {
        value.chars().take(limit).collect()
    }
}

fn end_slice(value: &str, limit: usize) -> String {
    let count = value.chars().count();
    if count <= limit {
        value.to_string()
    } else {
        value.chars().skip(count - limit).collect()
    }
}

fn tail(path: &Path, limit: usize) -> String {
    let Ok(bytes) = fs::read(path) else {
        return String::new();
    };
    end_slice(&String::from_utf8_lossy(&bytes), limit)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn server_lcov_keeps_shop_sources_without_zero_lines() {
        let raw = "SF:/work/shop-rs-api/src/lib.rs\nDA:0,1\nDA:2,1\nend_of_record\nSF:/work/other.rs\nDA:1,1\nend_of_record\n";
        let (kept, sample) = filter_lcov(raw);
        assert_eq!(sample, "/work/shop-rs-api/src/lib.rs");
        assert!(kept.contains("SF:/work/shop-rs-api/src/lib.rs\n"));
        assert!(kept.contains("DA:2,1\n"));
        assert!(kept.contains("end_of_record\n"));
        assert!(!kept.contains("DA:0,"));
        assert!(!kept.contains("other.rs"));
    }

    #[test]
    fn cargo_test_lines_map_status() {
        assert_eq!(
            parse_cargo_test_line("test shop_project_cart_suite_adds_line_to_cart_test ... ok")
                .unwrap()
                .1,
            "passed"
        );
        assert_eq!(
            parse_cargo_test_line("test rejects ... FAILED").unwrap().1,
            "failed"
        );
        assert_eq!(
            parse_cargo_test_line("test waits ... ignored").unwrap().1,
            "skipped"
        );
        assert!(parse_cargo_test_line("running 1 test").is_none());
    }

    #[test]
    fn env_file_strips_quotes_and_comments() {
        let path = std::env::temp_dir().join(format!("shop-adapter-env-{}", std::process::id()));
        fs::write(
            &path,
            "# comment\nSHOP_DB_USER=\"shop\"\nSHOP_DB_PASSWORD='secret'\nSHOP_DB_NAME=shop\n",
        )
        .unwrap();
        let values = read_env_file(&path);
        let _ = fs::remove_file(&path);
        assert_eq!(values.get("SHOP_DB_USER").map(String::as_str), Some("shop"));
        assert_eq!(
            values.get("SHOP_DB_PASSWORD").map(String::as_str),
            Some("secret")
        );
        assert_eq!(values.get("SHOP_DB_NAME").map(String::as_str), Some("shop"));
    }

    #[test]
    fn database_userinfo_is_percent_encoded() {
        assert_eq!(percent_encode("shop"), "shop");
        assert_eq!(percent_encode("a b"), "a%20b");
        assert_eq!(percent_encode("p@ss"), "p%40ss");
    }

    #[test]
    fn durations_truncate_toward_zero() {
        assert_eq!(whole_millis(&json!(1.9)), 1);
        assert_eq!(whole_millis(&json!(0.2)), 0);
        assert_eq!(whole_millis(&json!(-4.0)), 0);
        assert_eq!(whole_millis(&json!(12)), 12);
        assert_eq!(whole_millis(&Value::Null), 0);
        assert_eq!(prefix("abcdef", 3), "abc");
    }

    #[test]
    fn unsupported_phase_fails_before_building() {
        let directory =
            std::env::temp_dir().join(format!("shop-adapter-phase-{}", std::process::id()));
        let _ = fs::remove_dir_all(&directory);
        fs::create_dir_all(&directory).unwrap();
        let error = Run::new(Request {
            phase: "unit".into(),
            root: directory.clone(),
            output: directory.join("out"),
            coverage: true,
            env: BTreeMap::new(),
        })
        .execute()
        .unwrap_err();
        let _ = fs::remove_dir_all(&directory);
        assert!(error.to_string().contains("unsupported phase unit"));
    }

    #[test]
    fn a_failed_case_fails_the_phase() {
        let directory =
            std::env::temp_dir().join(format!("shop-adapter-fail-{}", std::process::id()));
        let mut run = Run::new(Request {
            phase: "integration".into(),
            root: directory.clone(),
            output: directory.join("out"),
            coverage: false,
            env: BTreeMap::new(),
        });
        run.tests.push(TestRecord {
            name: "case".into(),
            status: "failed".into(),
            duration_ms: 4,
            message: None,
            attachments: Vec::new(),
        });
        run.ok = true;
        let response = run.response();
        assert_eq!(response["success"], false);
        assert_eq!(response["tests"][0]["status"], "failed");
    }
}

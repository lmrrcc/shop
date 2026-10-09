import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import libCoverage from "istanbul-lib-coverage";
import libReport from "istanbul-lib-report";
import reports from "istanbul-reports";

const input = process.argv[2];
const directory = process.argv[3];
if (!input || !directory) {
  process.stderr.write("usage: write-lcov.mjs <coverage.jsonl> <output-dir>\n");
  process.exit(2);
}
const map = libCoverage.createCoverageMap({});
for (const line of readFileSync(input, "utf8").split("\n")) {
  if (line.trim()) map.merge(JSON.parse(line));
}
mkdirSync(directory, { recursive: true });
reports.create("lcovonly", { file: "browser.lcov" }).execute(libReport.createContext({ dir: directory, coverageMap: map }));
const lcovPath = `${directory}/browser.lcov`;
const records = readFileSync(lcovPath, "utf8").split("end_of_record\n");
const kept = records.filter((record) => record.trim()).map((record) => {
  const source = record.split("\n").find((line) => line.startsWith("SF:")) ?? "";
  if (!source.includes("shop-ts-lib-cart/")) return `${record}end_of_record\n`;
  const lines = record.split("\n").filter((line) => {
    if (line.startsWith("DA:")) return Number(line.slice(line.lastIndexOf(",") + 1)) > 0;
    if (line.startsWith("FNDA:")) return Number(line.slice(5).split(",")[0]) > 0;
    return line.length > 0;
  });
  return `${lines.join("\n")}\nend_of_record\n`;
});
writeFileSync(lcovPath, kept.join(""));

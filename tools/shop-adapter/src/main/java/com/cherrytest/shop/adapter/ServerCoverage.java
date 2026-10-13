package com.cherrytest.shop.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.tools.ExecFileLoader;

final class ServerCoverage {
    private ServerCoverage() {}

    static List<Path> executionFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".exec")).sorted().toList();
        }
    }

    static String lcov(List<Path> executionFiles, Path classes, Path sources) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        for (Path file : executionFiles) {
            loader.load(file.toFile());
        }
        CoverageBuilder builder = new CoverageBuilder();
        new Analyzer(loader.getExecutionDataStore(), builder).analyzeAll(classes.toFile());
        StringBuilder lcov = new StringBuilder();
        builder.getSourceFiles().stream()
                .sorted((left, right) -> sourcePath(sources, left).compareTo(sourcePath(sources, right)))
                .forEach(source -> appendRecord(lcov, sourcePath(sources, source), source));
        return lcov.toString();
    }

    private static String sourcePath(Path sources, ISourceFileCoverage source) {
        return sources.resolve(source.getPackageName()).resolve(source.getName()).toString();
    }

    private static void appendRecord(StringBuilder lcov, String path, ISourceFileCoverage source) {
        lcov.append("SF:").append(path).append('\n');
        for (int line = source.getFirstLine(); line > 0 && line <= source.getLastLine(); line++) {
            int status = source.getLine(line).getStatus();
            if (status == ICounter.EMPTY) {
                continue;
            }
            lcov.append("DA:").append(line).append(',').append(status == ICounter.NOT_COVERED ? 0 : 1).append('\n');
        }
        lcov.append("end_of_record\n");
    }
}

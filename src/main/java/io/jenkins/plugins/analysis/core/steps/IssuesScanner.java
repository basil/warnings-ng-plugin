package io.jenkins.plugins.analysis.core.steps;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import edu.hm.hafner.analysis.FingerprintGenerator;
import edu.hm.hafner.analysis.FullTextFingerprint;
import edu.hm.hafner.analysis.ModuleDetector;
import edu.hm.hafner.analysis.ModuleDetector.FileSystem;
import edu.hm.hafner.analysis.PackageNameResolver;
import edu.hm.hafner.analysis.Report;
import edu.hm.hafner.analysis.Report.IssueFilterBuilder;
import io.jenkins.plugins.analysis.core.filter.RegexpFilter;
import io.jenkins.plugins.analysis.core.model.Tool;
import io.jenkins.plugins.analysis.core.scm.Blamer;
import io.jenkins.plugins.analysis.core.scm.Blames;
import io.jenkins.plugins.analysis.core.util.AbsolutePathGenerator;
import io.jenkins.plugins.analysis.core.util.AffectedFilesResolver;
import io.jenkins.plugins.analysis.core.util.LogHandler;
import io.jenkins.plugins.analysis.core.util.ModuleResolver;
import jenkins.MasterToSlaveFileCallable;

import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Run;
import hudson.remoting.VirtualChannel;

/**
 * Scans report files or the console log for issues.
 *
 * @author Ullrich Hafner
 */
class IssuesScanner {
    private final FilePath jenkinsRootDir;
    private final Charset sourceCodeEncoding;
    private final Tool tool;
    private final List<RegexpFilter> filters;
    private final Blamer blamer;

    IssuesScanner(final Tool tool, final List<RegexpFilter> filters,
            final Charset sourceCodeEncoding, final FilePath jenkinsRootDir, final Blamer blamer) {
        this.filters = new ArrayList<>(filters);
        this.sourceCodeEncoding = sourceCodeEncoding;
        this.tool = tool;
        this.jenkinsRootDir = jenkinsRootDir;
        this.blamer = blamer;
    }

    public AnnotatedReport scan(final Run<?, ?> run, final FilePath workspace, final LogHandler logger)
            throws IOException, InterruptedException {
        Report report = tool.scan(run, workspace, logger);

        return postProcess(report, workspace, logger);
    }

    private AnnotatedReport postProcess(final Report report, final FilePath workspace, final LogHandler logger)
            throws IOException, InterruptedException {
        AnnotatedReport result;
        if (report.isEmpty()) {
            result = new AnnotatedReport(tool.getActualId(), report); // nothing to post process
            if (report.hasErrors()) {
                report.logInfo("Skipping post processing due to errors");
            }
        }
        else {
            report.logInfo("Post processing issues on '%s' with encoding '%s'", getAgentName(workspace),
                    sourceCodeEncoding);

            result = workspace.act(new ReportPostProcessor(tool.getActualId(), report, sourceCodeEncoding.name(),
                    jenkinsRootDir, blamer, filters));
        }
        logger.log(result.getReport());
        return result;
    }

    private String getAgentName(final FilePath workspace) {
        return StringUtils.defaultIfBlank(getComputerName(workspace), "Master");
    }

    private String getComputerName(final FilePath workspace) {
        Computer computer = workspace.toComputer();
        if (computer != null) {
            return computer.getName();
        }
        return StringUtils.EMPTY;
    }

    /**
     * Post processes the report on the build agent. Assigns absolute paths, package names, and module names and
     * computes fingerprints for each issue. Finally, for each file the SCM blames are computed.
     */
    private static class ReportPostProcessor extends MasterToSlaveFileCallable<AnnotatedReport> {
        private static final long serialVersionUID = -9138045560271783096L;

        private final String id;
        private final Report originalReport;
        private final String sourceCodeEncoding;
        private final FilePath jenkinsRootDir;
        private final Blamer blamer;
        private final List<RegexpFilter> filters;

        ReportPostProcessor(final String id, final Report report, final String sourceCodeEncoding,
                final FilePath jenkinsRootDir, final Blamer blamer, final List<RegexpFilter> filters) {
            super();

            this.id = id;
            originalReport = report;
            this.sourceCodeEncoding = sourceCodeEncoding;
            this.jenkinsRootDir = jenkinsRootDir;
            this.blamer = blamer;
            this.filters = filters;
        }

        @Override
        public AnnotatedReport invoke(final File workspace, final VirtualChannel channel) throws InterruptedException {
            resolveAbsolutePaths(originalReport, workspace);
            copyAffectedFiles(originalReport, workspace);
            resolveModuleNames(originalReport, workspace);
            resolvePackageNames(originalReport);

            Report filtered = filter(originalReport);
            filtered.stream().forEach(issue -> issue.setOrigin(id));
            createFingerprints(filtered);
            Blames blames = blamer.blame(filtered);
            return new AnnotatedReport(id, filtered, blames);
        }

        private void resolveAbsolutePaths(final Report report, final File workspace) {
            report.logInfo("Resolving absolute file names for all issues");

            AbsolutePathGenerator generator = new AbsolutePathGenerator();
            generator.run(report, workspace.toPath());
        }

        private Report filter(final Report report) {
            int actualFilterSize = 0;
            IssueFilterBuilder builder = new IssueFilterBuilder();
            for (RegexpFilter filter : filters) {
                if (StringUtils.isNotBlank(filter.getPattern())) {
                    filter.apply(builder);
                    actualFilterSize++;
                }
            }
            Report filtered = report.filter(builder.build());
            if (actualFilterSize > 0) {
                filtered.logInfo(
                        "Applying %d filters on the set of %d issues (%d issues have been removed, %d issues will be published)",
                        filters.size(), report.size(), report.size() - filtered.size(), filtered.size());
            }
            else {
                filtered.logInfo("No filter has been set, publishing all %d issues", filtered.size());
            }

            return filtered;
        }

        private void copyAffectedFiles(final Report report, final File workspace) throws InterruptedException {
            report.logInfo("Copying affected files to Jenkins' build folder %s", jenkinsRootDir);

            new AffectedFilesResolver().copyFilesWithAnnotationsToBuildFolder(report, jenkinsRootDir, workspace);
        }

        private void resolveModuleNames(final Report report, final File workspace) {
            report.logInfo("Resolving module names from module definitions (build.xml, pom.xml, or Manifest.mf files)");

            ModuleResolver resolver = new ModuleResolver();
            resolver.run(report, new ModuleDetector(workspace.toPath(), new DefaultFileSystem()));
        }

        private void resolvePackageNames(final Report report) {
            report.logInfo("Resolving package names (or namespaces) by parsing the affected files");

            PackageNameResolver resolver = new PackageNameResolver();
            resolver.run(report, getCharset());
        }

        private Charset getCharset() {
            return Charset.forName(sourceCodeEncoding);
        }

        private void createFingerprints(final Report report) {
            report.logInfo("Creating fingerprints for all affected code blocks to track issues over different builds");

            FingerprintGenerator generator = new FingerprintGenerator();
            generator.run(new FullTextFingerprint(), report, getCharset());
        }
    }

    /**
     * Provides file system operations using real IO.
     */
    private static final class DefaultFileSystem implements FileSystem {
        @Override
        public InputStream create(final String fileName) throws FileNotFoundException {
            return new FileInputStream(new File(fileName));
        }

        @Override
        public String[] find(final Path root, final String pattern) {
            return new FileFinder(pattern).find(root.toFile());
        }
    }
}

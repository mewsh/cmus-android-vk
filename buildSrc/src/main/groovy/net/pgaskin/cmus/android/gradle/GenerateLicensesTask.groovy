package net.pgaskin.cmus.android.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.util.regex.Pattern

// The third-party licenses aggregated into a single WebView asset, generated
// from each submodule's top-level license file(s) so it can't drift from what
// is actually built. One collapsible <details> per library, in the order the
// submodules are named.
abstract class GenerateLicensesTask extends DefaultTask {
    static final String ASSET = "licenses.html"

    // the files projects conventionally ship their license terms in
    private static final Pattern LICENSE_FILE = ~/(?i)^(licen[sc]e|copying|copyright|notice|unlicen[sc]e)([-._].*)?$/

    private static final Pattern GITMODULES_PATH = ~/^\s*path\s*=\s*(\S+)/
    private static final Pattern GITMODULES_URL = ~/^\s*url\s*=\s*(\S+)/

    // the submodule list, with the upstream url shown for each library
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getGitmodulesFile()

    // the submodule paths are resolved against this
    @Internal
    abstract DirectoryProperty getRepositoryDir()

    @Input
    abstract Property<String> getIntro()

    @OutputDirectory
    abstract DirectoryProperty getAssetsOutputDir()

    // discovered when the inputs are snapshotted rather than at configuration
    // time, so adding or removing a license file also re-runs the task
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    List<File> getLicenseFiles() {
        submodules().collectMany { licenseFilesIn(it.dir) }
    }

    @TaskAction
    void generate() {
        def libraries = submodules().collect { submodule ->
            def files = licenseFilesIn(submodule.dir)
            if (files.isEmpty()) {
                // everything bundled in the apk has to have its terms shown
                throw new GradleException("no license file in ${submodule.dir}"
                    + " (is the submodule initialized? run: git submodule update --init)")
            }
            submodule + [files: files]
        }

        def assetsRoot = assetsOutputDir.get().asFile
        assetsRoot.deleteDir()
        assetsRoot.mkdirs()
        new File(assetsRoot, ASSET).setText(renderHtml(libraries), "UTF-8")
    }

    // the submodules from .gitmodules, sorted by name
    List<Map> submodules() {
        def root = repositoryDir.get().asFile
        def submodules = []
        def path = null
        gitmodulesFile.get().asFile.eachLine("UTF-8") { String line ->
            def mp = GITMODULES_PATH.matcher(line)
            if (mp.find()) {
                path = mp.group(1)
                submodules.add([name: new File(path).name, dir: new File(root, path), url: null])
                return
            }
            def mu = GITMODULES_URL.matcher(line)
            if (mu.find() && path != null) {
                submodules.last().url = mu.group(1)
            }
        }
        return submodules.toSorted { a, b -> a.name <=> b.name }
    }

    static List<File> licenseFilesIn(File dir) {
        def files = new TreeMap<String, File>()
        dir.listFiles()?.each { file ->
            if (file.isFile() && LICENSE_FILE.matcher(file.name).matches()) {
                files.put(file.name, file)
            }
        }
        return files.values().toList()
    }

    String renderHtml(List<Map> libraries) {
        def body = new StringBuilder()
        libraries.each { library ->
            body << "<details>\n<summary>${htmlEscape(library.name.toString())}</summary>\n"
            if (library.url != null) {
                body << "<p class=\"src\"><a href=\"${htmlEscape(library.url.toString())}\">${htmlEscape(library.url.toString())}</a></p>\n"
            }
            def files = library.files as List<File>
            files.each { file ->
                if (files.size() > 1) {
                    body << "<div class=\"fname\">${htmlEscape(file.name)}</div>\n"
                }
                body << "<pre>${htmlEscape(file.getText("UTF-8"))}</pre>\n"
            }
            body << "</details>\n"
        }

        def css = '''
:root { color-scheme: light dark; }
body { font-family: sans-serif; margin: 0; padding: 28px 16px 40px; line-height: 1.5;
       background: #ffffff; color: #202124; }
h1 { font-size: 1.3rem; margin: 0 0 4px; }
.intro { opacity: .7; margin: 0 0 16px; }
details { margin: 10px 0; }
summary { padding: 16px; font-size: 1.05rem; font-weight: 600; cursor: pointer;
          border-radius: 8px; background: rgba(128,128,128,.14); min-height: 24px; }
.src { font-size: .82rem; margin: 10px 2px 0; overflow-wrap: anywhere; }
.fname { font-family: monospace; font-size: .8rem; opacity: .7; margin: 12px 2px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: .72rem; margin: 8px 0 0;
      padding: 12px; border: 1px solid rgba(128,128,128,.35); border-radius: 8px;
      background: rgba(128,128,128,.08); }
a { color: inherit; }
@media (prefers-color-scheme: dark) {
  body { background: #121212; color: #e3e3e3; }
}
'''

        """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>Third-party licenses</title>
<style>${css}</style>
</head>
<body>
<h1>Third-party licenses</h1>
<p class="intro">${htmlEscape(intro.get())}</p>
${body}</body>
</html>
"""
    }

    static String htmlEscape(String s) {
        s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    }
}

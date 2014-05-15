/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.haskforce.jps;

/*
 * Downloaded from https://github.com/Atsky/haskell-idea-plugin on 7 May
 * 2014.
 */

import com.haskforce.jps.model.HaskellBuildOptions;
import com.haskforce.jps.model.JpsHaskellBuildOptionsExtension;
import org.jetbrains.jps.incremental.BuilderCategory;
import org.jetbrains.jps.incremental.ModuleLevelBuilder;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.ModuleChunk;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ModuleBuildTarget;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.messages.ProgressMessage;
import org.jetbrains.jps.model.module.JpsModule;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First stop that does any work in the external builder.
 */
public class CabalBuilder extends ModuleLevelBuilder {
    // Messages go to the log available in Help -> Show log in finder,
    // "build-log" subdirectory.
    private final static Logger LOG = Logger.getInstance(CabalBuilder.class);

    public CabalBuilder() {
        super(BuilderCategory.TRANSLATOR);
    }

    public ExitCode build(final CompileContext context,
                          final ModuleChunk chunk,
                          final DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder,
                          final OutputConsumer outputConsumer) throws ProjectBuildException {
        try {
            for (JpsModule module : chunk.getModules()) {
                File cabalFile = getCabalFile(module);
                if (cabalFile == null) {
                    //context.processMessage(new CompilerMessage("cabal", BuildMessage.Kind.ERROR,
                    //        "Can't find cabal file in " + getContentRootPath(module)));
                    continue;
                }
                HaskellBuildOptions buildOptions = JpsHaskellBuildOptionsExtension.getOrCreateExtension(module.getProject()).getOptions();
                CabalJspInterface cabal = new CabalJspInterface(buildOptions.myCabalPath, cabalFile);

                if (runConfigure(context, module, cabal)) return ExitCode.ABORT;
                if (runBuild(context, module, cabal)) return ExitCode.ABORT;
            }
            return ExitCode.OK;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            context.processMessage(new CompilerMessage("cabal", BuildMessage.Kind.ERROR, e.getMessage()));
        }
        return ExitCode.ABORT;
    }

    private static boolean runBuild(CompileContext context, JpsModule module, CabalJspInterface cabal) throws IOException, InterruptedException {
        context.processMessage(new ProgressMessage("cabal build"));
        context.processMessage(new CompilerMessage("cabal", BuildMessage.Kind.INFO, "Start build"));
        Process buildProcess = cabal.build();
        processOut(context, buildProcess, module);

        if (buildProcess.waitFor() != 0) {
            context.processMessage(new CompilerMessage("cabal", BuildMessage.Kind.ERROR, "build errors."));
            return true;
        }
        return false;
    }

    private static boolean runConfigure(CompileContext context, JpsModule module, CabalJspInterface cabal) throws IOException, InterruptedException {
        context.processMessage(new CompilerMessage("cabal", BuildMessage.Kind.INFO, "Start configure"));

        Process configureProcess = cabal.configure();

        processOut(context, configureProcess, module);

        if (configureProcess.waitFor() != 0) {
            context.processMessage(new CompilerMessage(
                    "cabal",
                    BuildMessage.Kind.ERROR,
                    "configure failed."));
            return true;
        }
        return false;
    }

    private static void processOut(CompileContext context, Process process, JpsModule module) {
        final String warningPrefix = "Warning: ";
        boolean oneBehind = false;
        String line = "";
        Iterator<String> processOut = collectOutput(process);
        StringBuilder msg = new StringBuilder(1000);
        Pattern compiledPattern = Pattern.compile("(.*):(\\d+):(\\d+):\\s*(.*):(.*)");

        while (processOut.hasNext() || oneBehind) {
            if (oneBehind) {
                oneBehind = false;
            } else {
                line = processOut.next();
            }

            // See comment after this method for example warning message.
            Matcher matcher = compiledPattern.matcher(line);
            if (line.startsWith(warningPrefix)) {
                // Cabal messages
                String text = line.substring(warningPrefix.length()) + System.lineSeparator() + processOut.next();
                context.processMessage(new CompilerMessage("cabal", BuildMessage.Kind.WARNING, text));
            } else if (matcher.find()) {
                // GHC Messages
                String file = matcher.group(1);
                long lineNum = Long.parseLong(matcher.group(2));
                long colNum = Long.parseLong(matcher.group(3));
                msg.setLength(0);
                msg.append(matcher.group(5));
                while (processOut.hasNext()) {
                    line = processOut.next();

                    if (line.endsWith("warning generated.") ||
                            line.trim().length() == 0) {
                        break;
                    }
                    if (line.startsWith("[") || line.startsWith("In-place")) {
                        // Fresh line starting, save to process next.
                        oneBehind = true;
                        break;
                    }
                    msg.append(line).append(System.lineSeparator());
                }

                // RootPath necessary for reasonable error messages by Intellij.
                String sourcePath = getContentRootPath(module) + File.separator + file.replace('\\', File.separatorChar);
                BuildMessage.Kind kind = matcher.group(4).contains("arn") ?
                       BuildMessage.Kind.WARNING : BuildMessage.Kind.ERROR;

                final String trimmedMessage = msg.toString().trim();
                context.processMessage(new CompilerMessage(
                        "ghc",
                        kind,
                        trimmedMessage,
                        sourcePath,
                        -1L, -1L, -1L,
                        lineNum, colNum));
            }
        }
    }
    /* Example warning:

Preprocessing library feldspar-language-0.6.1.0...
[74 of 92] Compiling Feldspar.Core.UntypedRepresentation ( src/Feldspar/Core/UntypedRepresentation.hs, dist/build/Feldspar/Core/UntypedRepresentation.o )
src/Feldspar/Core/UntypedRepresentation.hs:483:5: Warning:
    Pattern match(es) are overlapped
    In an equation for `typeof': typeof e = ...
[74 of 92] Compiling Feldspar.Core.UntypedRepresentation ( src/Feldspar/Core/UntypedRepresentation.hs, dist/build/Feldspar/Core/UntypedRepresentation.p_o )
<same warning again>

     */

    private static Iterator<String> collectOutput(Process process) {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        return new Iterator<String>() {

            String line = null;

            @Override
            public boolean hasNext() {
                return fetch() != null;
            }

            private String fetch() {
                if (line == null) {
                    try {
                        line = reader.readLine();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return line;
            }

            @Override
            public String next() throws NoSuchElementException {
                String result = fetch();
                line = null;
                return result;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static File getCabalFile(JpsModule module) {
        String pathname = getContentRootPath(module);
        //noinspection ConstantConditions
        for (File file : new File(pathname).listFiles()) {
            if (file.getName().endsWith(".cabal")) {
                return file;
            }
        }
        return null;
    }

    private static String getContentRootPath(JpsModule module) {
        String url = module.getContentRootsList().getUrls().get(0);
        return url.substring("file://".length());
    }

    @Override
    public List<String> getCompilableFileExtensions() {
        return Arrays.asList("hs", "lhs");
    }

    @Override
    public String toString() {
        return getPresentableName();
    }

    @NotNull
    public String getPresentableName() {
        return "Cabal builder";
    }
}
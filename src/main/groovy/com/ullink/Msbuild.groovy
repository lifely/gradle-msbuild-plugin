package com.ullink

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.internal.ConventionTask
import org.gradle.api.tasks.StopActionException
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskOutputs
import org.gradle.internal.os.OperatingSystem

class Msbuild extends ConventionTask {

    Map<String, Object> environment
    def libraryPath
    def headerPath
    
    String version
    String msbuildDir
    def solutionFile
    def projectFile
    String loggerAssembly
    Boolean optimize
    Boolean debugSymbols
    String debugType
    String platform
    def destinationDir
    def intermediateDir
    Boolean generateDoc
    String projectName
    String configuration
    List<String> defineConstants
    List<String> targets
    String verbosity
    Map<String, Object> parameters = [:]
    Map<String, ProjectFileParser> allProjects = [:]
    String executable
    ProjectFileParser projectParsed
    IExecutableResolver resolver
    
    Msbuild() {
        description = 'Executes MSBuild on the specified project/solution'
        resolver =
                OperatingSystem.current().windows ? new MsbuildResolver() : new XbuildResolver()

        resolver.setupExecutable(this)

        if (msbuildDir == null) {
            throw new StopActionException("$executable not found")
        }
        conventionMapping.map "solutionFile", {
            project.file(project.name + ".sln").exists() ? project.name + ".sln" : null
        }
        conventionMapping.map "projectFile", {
            project.file(project.name + ".csproj").exists() ? project.name + ".csproj" : null
        }
        conventionMapping.map "projectName", { project.name }
        inputs.files {
            if (isSolutionBuild()) {
                project.file(getSolutionFile())
            } else if (isProjectBuild()) {
                project.file(getProjectFile())
            }
        }
        inputs.files {
            if (resolveProject()) {
                projectParsed.gatherInputs()
            }
        }
        outputs.upToDateWhen {
            resolveProject()
        }
        TaskOutputs output = outputs.dir {
            if (resolveProject()) {
                projectParsed.getOutputDirs().collect {
                    project.fileTree(dir: it, excludes: ['*.vshost.exe', '*.vshost.exe.*'])
                }
            }
        }
        output
    }

    boolean isSolutionBuild() {
        projectFile == null && getSolutionFile() != null
    }

    boolean isProjectBuild() {
        solutionFile == null && getProjectFile() != null
    }

    Map<String, ProjectFileParser> getProjects() {
        if (resolveProject()) {
            allProjects
        }
    }

    ProjectFileParser getMainProject() {
        if (resolveProject()) {
            projectParsed
        }
    }

    def parseProjectFile(def file) {
        def tmp = File.createTempFile('ProjectFileParser', '.exe')
        try {
            def src = getClass().getResourceAsStream('/META-INF/bin/ProjectFileParser.exe')
            tmp.newOutputStream().leftShift(src).close();
            def builder = resolver.executeDotNet(tmp)
            builder.command().add(file.toString())
            def proc = builder.start()
            try {
                proc.out.leftShift(JsonOutput.toJson(getInitProperties())).close()
                return new JsonSlurper().parse(new InputStreamReader(proc.in))
            }
            finally {
                if (proc.waitFor() != 0) {
                    proc.err.eachLine { line ->
                        logger.error line
                    }
                    throw new GradleException('Project file parsing failed')
                }
            }
        } finally {
            tmp.delete()
        }
    }

    boolean resolveProject() {
        if (projectParsed == null) {
            if (isSolutionBuild()) {
                def result = parseProjectFile(getSolutionFile())
                allProjects = result.collectEntries { [it.key, new ProjectFileParser(msbuild: this, eval: it.value)] }
                projectParsed = allProjects[getProjectName()]
            } else if (isProjectBuild()) {
                projectParsed = new ProjectFileParser(msbuild: this, eval: parseProjectFile(getProjectFile()))
                allProjects[projectParsed.projectName] = projectParsed
            }
        }
        projectParsed != null
    }

    void setTarget(String s) {
        targets = [s]
    }

    @TaskAction
    def build() {

        def commandLineArgs = [new File(msbuildDir, executable)]

        commandLineArgs += '/nologo'

        if (isSolutionBuild()) {
            commandLineArgs += project.file(getSolutionFile())
        } else if (isProjectBuild()) {
            commandLineArgs += project.file(getProjectFile())
        }

        if (loggerAssembly) {
            commandLineArgs += '/l:' + loggerAssembly
        }
        if (targets && !targets.isEmpty()) {
            commandLineArgs += '/t:' + targets.join(';')
        }
        String verb = verbosity
        if (!verb) {
            if (logger.debugEnabled) {
                verb = 'detailed'
            } else if (logger.infoEnabled) {
                verb = 'normal'
            } else {
                verb = 'minimal' // 'quiet'
            }
        }
        if (verb) {
            commandLineArgs += '/v:' + verb
        }

        def cmdParameters = getInitProperties()

        cmdParameters.each {
            if (it.value) {
                commandLineArgs += '/p:' + it.key + '=' + it.value
            }
        }

        project.exec {
            logger.debug "MSBuild-Gradle: the build is about to start, here the environment used: ${this.environment}."
            environment = this.environment
            commandLine = commandLineArgs
        }
    }
    
    Map<String, Object> getEnvironment() {
        def env_ = [:] + System.env
    
        if (project.hasProperty('environment') && project.environment) {
            logger.info "MSBuild-Gradle: Detected project-wide environement, we'll use it."
            env_ << project.environment   
        }
    
        if (environment && environment.size() > 0)
            env_ << environment
    
        def include = this.getHeaderPath()
        def library = this.getLibraryPath()
        
        logger.info("MSBuild-Gradle: Library arg: ${library}")
        
        if (include || library)
            env_ << ['UseEnv' : 'true']
        
        if (include) include.each() { env_["INCLUDE"] += ";${it}" }
        if (library) library.each() { env_["LIB"] += ";${it}" }
        
        logger.info("MSBuild-Gradle: environment include: ${env_.INCLUDE}.")
        logger.info("MSBuild-Gradle: environment library: ${env_.LIB}.")
        
        // env_ << this.headerPath
        // env_ << this.libraryPath
        
        return env_
    }
    
    def getLibraryPath() {
        logger.info("MSBuild-Gradle: Library argument: ${libraryPath} - instanceOf ${libraryPath ? libraryPath.class: "null"}")
        if (libraryPath instanceof String || libraryPath instanceof GString)
            return [libraryPath]
        else if (libraryPath instanceof List<String>)
            return libraryPath
        else
            return null
    }
    
    def getHeaderPath() {
        logger.info("MSBuild-Gradle: Include argument: ${headerPath} - instanceOf ${headerPath ? headerPath.class : "null"}")
        if (headerPath instanceof String || headerPath instanceof GString)
            return [headerPath]
        else if (headerPath instanceof List<String>)
            return headerPath
        else
            return null
    }

    Map getInitProperties() {
        def cmdParameters = new HashMap<String, Object>()
        if (parameters != null) {
            cmdParameters.putAll(parameters)
        }
        cmdParameters.Project = getProjectName()
        cmdParameters.GenerateDocumentation = generateDoc
        cmdParameters.DebugType = debugType
        cmdParameters.Optimize = optimize
        cmdParameters.DebugSymbols = debugSymbols
        cmdParameters.OutputPath = destinationDir == null ? null : project.file(destinationDir)
        cmdParameters.IntermediateOutputPath = intermediateDir == null ? null : project.file(intermediateDir)
        cmdParameters.Configuration = configuration
        cmdParameters.Platform = platform
        if (defineConstants != null && !defineConstants.isEmpty()) {
            cmdParameters.DefineConstants = defineConstants.join(';')
        }
        def iter = cmdParameters.iterator()
        while (iter.hasNext()) {
            Map.Entry<String, Object> entry = iter.next()
            if (entry.value == null) {
                iter.remove()
            } else if (entry.value instanceof File) {
                entry.value = entry.value.path
            } else if (!entry.value instanceof String) {
                entry.value = entry.value.toString()
            }
        }
        ['OutDir', 'OutputPath', 'BaseIntermediateOutputPath', 'IntermediateOutputPath', 'PublishDir'].each {
            if (cmdParameters[it] && !cmdParameters[it].endsWith('\\')) {
                cmdParameters[it] += '\\'
            }
        }
        return cmdParameters
    }
}

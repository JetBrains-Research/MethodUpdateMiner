import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.file
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import git4idea.GitCommit
import git4idea.GitVcs
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.research.commentupdater.dataset.RawDatasetSample
import org.jetbrains.research.commentupdater.models.MetricsCalculator
import org.jetbrains.research.commentupdater.models.config.ModelFilesConfig
import org.jetbrains.research.commentupdater.processors.MethodChangesExtractor
import org.jetbrains.research.commentupdater.processors.ProjectMethodExtractor
import org.jetbrains.research.commentupdater.processors.RefactoringExtractor
import org.jetbrains.research.commentupdater.utils.PsiUtil
import org.jetbrains.research.commentupdater.utils.qualifiedName
import org.jetbrains.research.commentupdater.utils.textWithoutDoc
import kotlin.system.exitProcess


class PluginRunner : ApplicationStarter {
    override fun getCommandName(): String = "CommentUpdater"

    override fun main(args: Array<String>) {
        CodeCommentExtractor().main(args.drop(1))
    }
}

class CodeCommentExtractor : CliktCommand() {
    private val writeMutex = Mutex()

    private val dataset by argument(help = "Path to dataset").file(mustExist = true, canBeDir = false)
    private val output by argument(help = "Output directory").file(canBeFile = false)
    private val config by argument(help = "Model config").file(canBeFile = false)
    private val statsOutput by argument(help = "Output file for statistic").file(canBeDir = false)

    lateinit var rawSampleWriter: RawSampleWriter
    lateinit var statisticWriter: StatisticWriter

    private val statsHandler = StatisticHandler()

    private lateinit var metricsModel: MetricsCalculator

    companion object {
        enum class LogLevel {
            INFO, WARN, ERROR
        }

        var projectTag: String = ""
        var projectProcess: String = ""

        fun log(
            level: LogLevel, message: String, logThread: Boolean = false,
            applicationTag: String = "[HeadlessCommentUpdater]"
        ) {
            val fullLogMessage =
                "$level ${if (logThread) Thread.currentThread().name else ""} $applicationTag [$projectTag $projectProcess] $message"

            when (level) {
                LogLevel.INFO -> {
                    println(fullLogMessage)
                }
                LogLevel.WARN -> {
                    System.err.println(fullLogMessage)
                }
                LogLevel.ERROR -> {
                    System.err.println(fullLogMessage)
                }
            }
        }
    }

    override fun run() {
        log(LogLevel.INFO, "Starting Application")

        val inputFile = dataset

        metricsModel = MetricsCalculator(ModelFilesConfig(config))

        rawSampleWriter = RawSampleWriter(output)
        statisticWriter = StatisticWriter(statsOutput)
        statisticWriter.open()

        val projectPaths = inputFile.readLines()

        // You want to launch your processing work in different thread,
        // because runnable inside runAfterInitialization is executed after mappings and after this main method ends

        projectPaths.forEachIndexed { index, projectPath ->
            rawSampleWriter.setProjectFile(projectPath)
            projectTag = rawSampleWriter.projectName
            projectProcess = "${index + 1}/${projectPaths.size}"

            onStart()

            collectProjectExamples(projectPath)

            statisticWriter.saveStatistics(
                StatisticWriter.ProjectStatistic(
                    projectName = projectTag,
                    numOfMethods = statsHandler.numOfMethods.get(),
                    numOfDocMethods = statsHandler.numOfDocMethods.get()
                )
            )
            onFinish()
        }

        projectTag = ""
        statisticWriter.close()
        log(LogLevel.INFO, "Finished with ${statsHandler.totalExamplesNumber.get()} examples found.")
        exitProcess(0)

    }

    private fun onStart() {
        log(LogLevel.INFO, "Open project")
        rawSampleWriter.open()
    }

    private fun onFinish() {
        log(LogLevel.INFO, "Close project. ${statsHandler.reportSamples()}")
        rawSampleWriter.close()
        statsHandler.refresh()
    }

    private fun collectProjectExamples(projectPath: String) {
        val project = ProjectUtil.openOrImport(projectPath, null, true)
        if (project == null) {
            log(LogLevel.WARN, "Can't open project $projectPath")
            return
        }

        val vcsManager = PsiUtil.vcsSetup(project, projectPath)

        val gitRepoManager = ServiceManager.getService(
            project,
            GitRepositoryManager::class.java
        )

        try {
            val gitRoots = vcsManager.getRootsUnderVcs(GitVcs.getInstance(project))
            for (root in gitRoots) {
                val repo = gitRepoManager.getRepositoryForRoot(root) ?: continue
                runBlocking {
                    val commits = repo.walkAll()
                    statsHandler.numberOfCommits = commits.size

                    commits.map { commit ->
                        async(Dispatchers.Default) {
                            processCommit(commit, project)
                        }
                    }.awaitAll()
                }

            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed with an exception: ${e.message}")
        }
    }

    private suspend fun processCommit(
        commit: GitCommit,
        project: Project
    ) {
        try {
            commit.filterChanges(".java").forEach { change ->
                val fileName = change.afterRevision?.file?.name ?: ""
                log(
                    LogLevel.INFO,
                    "Commit: ${commit.id.toShortString()} num ~ ${statsHandler.processedCommits.get()}" +
                            "/${statsHandler.numberOfCommits} File changed: $fileName",
                    logThread = true
                )

                collectChange(change, commit, project)
            }
            statsHandler.processedCommits.incrementAndGet()
        } catch (e: Exception) {
            // In case of exception inside commit processing, just continue working and log exception
            // We don't want to fall because of strange mistake on single commit
            log(
                LogLevel.ERROR,
                "Error during commit ${commit.id.toShortString()} processing: ${e.message}",
                logThread = true
            )
        }
    }

    private suspend fun collectChange(
        change: Change,
        commit: GitCommit,
        project: Project
    ) {
        statsHandler.processedFileChanges.incrementAndGet()

        val newFileName = change.afterRevision?.file?.name ?: ""

        val refactorings = RefactoringExtractor.extract(change)
        val methodsStatistic = hashMapOf<String, Int>()
        val changedMethods = try {
            ProjectMethodExtractor.extractChangedMethods(
                project, change, refactorings,
                statisticContext = methodsStatistic
            )
        } catch (e: VcsException) {
            log(LogLevel.WARN, "Unexpected VCS exception: ${e.message}", logThread = true)
            null
        }

        statsHandler.numOfMethods.addAndGet(methodsStatistic["numOfMethods"]!!)
        statsHandler.numOfDocMethods.addAndGet(methodsStatistic["numOfDocMethods"]!!)

        changedMethods?.let {
            for ((oldMethod, newMethod) in it) {
                statsHandler.processedMethods.incrementAndGet()
                lateinit var newMethodName: String
                lateinit var oldMethodName: String
                lateinit var oldCode: String
                lateinit var newCode: String
                lateinit var oldComment: String
                lateinit var newComment: String

                ApplicationManager.getApplication().runReadAction {
                    newMethodName = newMethod.qualifiedName
                    oldMethodName = oldMethod.qualifiedName
                    oldCode = oldMethod.textWithoutDoc
                    newCode = newMethod.textWithoutDoc
                    oldComment = oldMethod.docComment?.text ?: ""
                    newComment = newMethod.docComment?.text ?: ""
                }

                if (oldCode.trim() == newCode.trim() && oldComment.trim() == newComment.trim()) {
                    continue
                }

                if (!MethodChangesExtractor.checkMethodChanged(
                        oldComment = oldComment,
                        newComment = newComment,
                        oldCode = oldCode,
                        newCode = newCode
                    )
                ) {
                    continue
                }

                statsHandler.foundExamples.incrementAndGet()

                val datasetExample = RawDatasetSample(
                    oldCode = oldCode,
                    newCode = newCode,
                    oldComment = oldComment,
                    newComment = newComment,
                    commitId = commit.id.toString(),
                    newFileName = newFileName,
                    commitTime = commit.timestamp.toString(),
                    oldMethodName = oldMethodName,
                    newMethodName = newMethodName
                )

                writeMutex.withLock {
                    rawSampleWriter.saveMetrics(datasetExample)
                }
            }
        }
    }
}



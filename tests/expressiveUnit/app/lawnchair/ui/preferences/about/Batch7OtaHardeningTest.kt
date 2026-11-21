package app.lawnchair.ui.preferences.about

import android.app.Application
import android.app.job.JobScheduler
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.util.getApkVersionComparison
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class Batch7OtaHardeningTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun resetState() {
        context.getSharedPreferences("expressive_update_notifications", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("expressive_update_channels", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSystemService(JobScheduler::class.java).cancelAll()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    @Test
    fun isSecureHttpsUrl_acceptsCleanHttpsAndRejectsHttpCredentialsAndMalformed() {
        assertThat(isSecureHttpsUrl("https://github.com/denson9874/ExpressiveLauncher/releases/download/v1/app.apk"))
            .isTrue()
        listOf(
            "http://github.com/denson9874/ExpressiveLauncher/app.apk",
            "https://user:pass@github.com/denson9874/ExpressiveLauncher/app.apk",
            "https://",
            "https://[broken",
            "file:///data/local/tmp/update.apk",
            "",
        ).forEach { unsafeUrl ->
            assertThat(isSecureHttpsUrl(unsafeUrl)).isFalse()
        }
    }

    @Test
    fun isValidNightlyAsset_requiresBothHttpsUrlAndValidSha256() {
        val validHash = "a".repeat(64)
        val validUrl = "https://github.com/LawnchairLauncher/lawnchair/releases/download/nightly/app.apk"

        assertThat(isValidNightlyAsset(validUrl, validHash)).isTrue()
        assertThat(isValidNightlyAsset(validUrl, null)).isFalse()
        assertThat(isValidNightlyAsset(validUrl, "not-a-sha256")).isFalse()
        assertThat(isValidNightlyAsset("http://example.com/app.apk", validHash)).isFalse()
        assertThat(isValidNightlyAsset("https://user:secret@example.com/app.apk", validHash)).isFalse()
    }

    @Test
    fun pruneStaleUpdateApks_deletesOldApksWhilePreservingActiveAndNonApkFiles() {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val staleApk = File(updatesDir, "Expressive-update-old.apk").apply { writeText("old") }
        val activeApk = File(updatesDir, "Expressive-update-active.apk").apply { writeText("active") }
        val unrelatedNote = File(updatesDir, "notes.txt").apply { writeText("keep") }

        pruneStaleUpdateApks(updatesDir, keepFiles = setOf(activeApk))

        assertThat(staleApk.exists()).isFalse()
        assertThat(activeApk.exists()).isTrue()
        assertThat(unrelatedNote.exists()).isTrue()
    }

    @Test
    fun validateUpdateApk_failsClosedWhenSha256MissingOrMismatchedOnNightlyChannel() {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(updatesDir, "nightly.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val missingDigest = UpdateState.Available(
            name = "nightly.apk",
            url = "https://github.com/LawnchairLauncher/lawnchair/releases/download/nightly/nightly.apk",
            changelogState = null,
            expectedSha256 = null,
            expectedPackageName = null,
            expectedVersionCode = null,
        )
        val wrongDigest = missingDigest.copy(expectedSha256 = "0".repeat(64))

        assertThat(
            context.validateUpdateApk(file, missingDigest, isExpressiveChannel = false, verifyBytes = true),
        ).isFalse()
        assertThat(
            context.validateUpdateApk(file, wrongDigest, isExpressiveChannel = false, verifyBytes = true),
        ).isFalse()
    }

    @Test
    fun checkNightlyUpdate_rejectsInsecureUrlAndMissingSha256() {
        val expectedBranch = "${context.getApkVersionComparison().first[0]}-dev"
        val badAssets = listOf(
            GitHubRelease.GitHubAsset(
                name = "Lawnchair.Debug.Nightly-CI_999999-abcdef1.apk",
                browserDownloadUrl = "http://insecure.example.com/nightly.apk",
                digest = "sha256:${"a".repeat(64)}",
                size = 16L,
            ),
            GitHubRelease.GitHubAsset(
                name = "Lawnchair.Debug.Nightly-CI_999999-abcdef1.apk",
                browserDownloadUrl = "https://github.com/LawnchairLauncher/lawnchair/releases/download/nightly/nightly.apk",
                digest = null,
                size = 16L,
            ),
        )

        badAssets.forEach { asset ->
            val api = FakeGitHubService(
                releases = listOf(
                    GitHubRelease(
                        tagName = "nightly",
                        targetCommitish = expectedBranch,
                        assets = listOf(asset),
                    ),
                ),
            )
            val repo = NightlyBuildsRepository(applicationContext = context, api = api)
            try {
                repo.checkForUpdate()
                awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Failed }
                assertThat(repo.updateState.value).isEqualTo(UpdateState.Failed)
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun installUpdate_validatesOnIoThreadLaunchesOnMainThreadAndPrunesStaleApks() {
        val expectedBranch = "${context.getApkVersionComparison().first[0]}-dev"
        val payload = "verified-nightly-apk-payload".toByteArray()
        val payloadHash = sha256Hex(payload)
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val staleApk = File(updatesDir, "Expressive-update-stale.apk").apply { writeText("stale") }

        val ioExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "batch7-io") }
        val mainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "batch7-main") }
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()

        val revalidateThread = AtomicReference<String?>()
        val launchThread = AtomicReference<String?>()
        val validatorSawVerifyBytes = AtomicBoolean(false)
        val launched = CountDownLatch(1)

        val api = FakeGitHubService(
            releases = listOf(
                GitHubRelease(
                    tagName = "nightly",
                    targetCommitish = expectedBranch,
                    assets = listOf(
                        GitHubRelease.GitHubAsset(
                            name = "Lawnchair.Debug.Nightly-CI_999999-abcdef1.apk",
                            browserDownloadUrl = "https://github.com/LawnchairLauncher/lawnchair/releases/download/nightly/app.apk",
                            digest = "sha256:$payloadHash",
                            size = payload.size.toLong(),
                        ),
                    ),
                ),
            ),
            downloadBytes = payload,
        )

        val repo = NightlyBuildsRepository(
            applicationContext = context,
            api = api,
            ioDispatcher = ioDispatcher,
            mainDispatcher = mainDispatcher,
            apkValidator = { _, _, isExpressive, verifyBytes ->
                assertThat(isExpressive).isFalse()
                if (verifyBytes) {
                    validatorSawVerifyBytes.set(true)
                    revalidateThread.set(Thread.currentThread().name)
                }
                true
            },
            canInstallPackages = { true },
            installerLauncher = {
                launchThread.set(Thread.currentThread().name)
                launched.countDown()
            },
        )

        try {
            repo.checkForUpdate()
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Available }

            repo.downloadUpdate(installAfterDownload = false)
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Downloaded }
            val downloadedFile = (repo.updateState.value as UpdateState.Downloaded).file

            assertThat(staleApk.exists()).isFalse()
            assertThat(downloadedFile.exists()).isTrue()

            repo.installUpdate(downloadedFile, forceInstall = true)
            assertThat(launched.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(validatorSawVerifyBytes.get()).isTrue()
            assertThat(revalidateThread.get()).startsWith("batch7-io")
            assertThat(launchThread.get()).startsWith("batch7-main")
        } finally {
            repo.close()
            ioExecutor.shutdownNow()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun installUpdate_catchesActivityNotFoundExceptionAndTransitionsToFailed() {
        val expectedBranch = "${context.getApkVersionComparison().first[0]}-dev"
        val payload = "no-installer-payload".toByteArray()
        val payloadHash = sha256Hex(payload)

        val ioExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "batch7-io-anfe") }
        val mainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "batch7-main-anfe") }
        val api = FakeGitHubService(
            releases = listOf(
                GitHubRelease(
                    tagName = "nightly",
                    targetCommitish = expectedBranch,
                    assets = listOf(
                        GitHubRelease.GitHubAsset(
                            name = "Lawnchair.Debug.Nightly-CI_999999-abcdef1.apk",
                            browserDownloadUrl = "https://github.com/LawnchairLauncher/lawnchair/releases/download/nightly/app.apk",
                            digest = "sha256:$payloadHash",
                            size = payload.size.toLong(),
                        ),
                    ),
                ),
            ),
            downloadBytes = payload,
        )
        val repo = NightlyBuildsRepository(
            applicationContext = context,
            api = api,
            ioDispatcher = ioExecutor.asCoroutineDispatcher(),
            mainDispatcher = mainExecutor.asCoroutineDispatcher(),
            apkValidator = { _, _, _, _ -> true },
            canInstallPackages = { true },
            installerLauncher = { throw ActivityNotFoundException("No installer present") },
        )

        try {
            repo.checkForUpdate()
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Available }

            repo.downloadUpdate(installAfterDownload = false)
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Downloaded }
            val downloadedFile = (repo.updateState.value as UpdateState.Downloaded).file

            repo.installUpdate(downloadedFile, forceInstall = true)
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Failed }
            assertThat(repo.updateState.value).isEqualTo(UpdateState.Failed)
        } finally {
            repo.close()
            ioExecutor.shutdownNow()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun installUpdate_validationFailureDeletesDownloadAndDoesNotLaunchInstaller() {
        val expectedBranch = "${context.getApkVersionComparison().first[0]}-dev"
        val payload = "tampered-after-download".toByteArray()
        val payloadHash = sha256Hex(payload)
        val launchAttempts = AtomicInteger(0)

        val ioExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "batch7-io-fail") }
        val mainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "batch7-main-fail") }
        val api = FakeGitHubService(
            releases = listOf(
                GitHubRelease(
                    tagName = "nightly",
                    targetCommitish = expectedBranch,
                    assets = listOf(
                        GitHubRelease.GitHubAsset(
                            name = "Lawnchair.Debug.Nightly-CI_999999-abcdef1.apk",
                            browserDownloadUrl = "https://github.com/LawnchairLauncher/lawnchair/releases/download/nightly/app.apk",
                            digest = "sha256:$payloadHash",
                            size = payload.size.toLong(),
                        ),
                    ),
                ),
            ),
            downloadBytes = payload,
        )
        val repo = NightlyBuildsRepository(
            applicationContext = context,
            api = api,
            ioDispatcher = ioExecutor.asCoroutineDispatcher(),
            mainDispatcher = mainExecutor.asCoroutineDispatcher(),
            apkValidator = { _, _, _, verifyBytes -> !verifyBytes },
            canInstallPackages = { true },
            installerLauncher = { launchAttempts.incrementAndGet() },
        )

        try {
            repo.checkForUpdate()
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Available }

            repo.downloadUpdate(installAfterDownload = false)
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Downloaded }
            val downloadedFile = (repo.updateState.value as UpdateState.Downloaded).file

            repo.installUpdate(downloadedFile, forceInstall = true)
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Failed }
            assertThat(launchAttempts.get()).isEqualTo(0)
            assertThat(downloadedFile.exists()).isFalse()
        } finally {
            repo.close()
            ioExecutor.shutdownNow()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun installUpdate_rapidDoubleInvocationLaunchesInstallerOnce() {
        val expectedBranch = "${context.getApkVersionComparison().first[0]}-dev"
        val payload = "double-tap-install-payload".toByteArray()
        val payloadHash = sha256Hex(payload)
        val firstValidationEntered = CountDownLatch(1)
        val allowFirstValidationFinish = CountDownLatch(1)
        val validationCount = AtomicInteger(0)
        val launchCount = AtomicInteger(0)
        val launched = CountDownLatch(1)

        val ioExecutor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "batch7-io-race") }
        val mainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "batch7-main-race") }
        val api = FakeGitHubService(
            releases = listOf(
                GitHubRelease(
                    tagName = "nightly",
                    targetCommitish = expectedBranch,
                    assets = listOf(
                        GitHubRelease.GitHubAsset(
                            name = "Lawnchair.Debug.Nightly-CI_999999-abcdef1.apk",
                            browserDownloadUrl = "https://github.com/LawnchairLauncher/lawnchair/releases/download/nightly/app.apk",
                            digest = "sha256:$payloadHash",
                            size = payload.size.toLong(),
                        ),
                    ),
                ),
            ),
            downloadBytes = payload,
        )
        val repo = NightlyBuildsRepository(
            applicationContext = context,
            api = api,
            ioDispatcher = ioExecutor.asCoroutineDispatcher(),
            mainDispatcher = mainExecutor.asCoroutineDispatcher(),
            apkValidator = { _, _, _, verifyBytes ->
                if (verifyBytes && validationCount.incrementAndGet() == 1) {
                    firstValidationEntered.countDown()
                    allowFirstValidationFinish.await(5, TimeUnit.SECONDS)
                }
                true
            },
            canInstallPackages = { true },
            installerLauncher = {
                launchCount.incrementAndGet()
                launched.countDown()
            },
        )

        try {
            repo.checkForUpdate()
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Available }

            repo.downloadUpdate(installAfterDownload = false)
            awaitCondition(timeoutMs = 5_000L) { repo.updateState.value is UpdateState.Downloaded }
            val downloadedFile = (repo.updateState.value as UpdateState.Downloaded).file

            repo.installUpdate(downloadedFile, forceInstall = true)
            assertThat(firstValidationEntered.await(5, TimeUnit.SECONDS)).isTrue()
            repo.installUpdate(downloadedFile, forceInstall = true)
            allowFirstValidationFinish.countDown()

            assertThat(launched.await(5, TimeUnit.SECONDS)).isTrue()
            // Drain both executors briefly to prove the superseded first install never launches.
            Thread.sleep(100)
            assertThat(launchCount.get()).isEqualTo(1)
        } finally {
            allowFirstValidationFinish.countDown()
            repo.close()
            ioExecutor.shutdownNow()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun shouldScheduleImmediateCheck_enforcesCooldownAndHandlesClockRollback() {
        val cooldown = ExpressiveUpdateScheduler.IMMEDIATE_CHECK_COOLDOWN_MILLIS
        val t0 = 1_000_000_000L

        assertThat(shouldScheduleImmediateCheck(lastScheduledMillis = 0L, nowMillis = t0, cooldownMillis = cooldown))
            .isTrue()
        assertThat(shouldScheduleImmediateCheck(lastScheduledMillis = t0, nowMillis = t0 + 1_000L, cooldownMillis = cooldown))
            .isFalse()
        assertThat(shouldScheduleImmediateCheck(lastScheduledMillis = t0, nowMillis = t0 + cooldown - 1L, cooldownMillis = cooldown))
            .isFalse()
        assertThat(shouldScheduleImmediateCheck(lastScheduledMillis = t0, nowMillis = t0 + cooldown, cooldownMillis = cooldown))
            .isTrue()
        assertThat(shouldScheduleImmediateCheck(lastScheduledMillis = t0, nowMillis = t0 - 10_000L, cooldownMillis = cooldown))
            .isTrue()
    }

    @Test
    fun ensureScheduled_doesNotResetPendingPeriodicJobAndGatesImmediateCheckBehindCooldown() {
        val config = ExpressiveUpdateConfig(
            channel = ExpressiveUpdateChannel.QA,
            manifestUrl = "https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa-v2/latest.json",
        )
        val jobScheduler = context.getSystemService(JobScheduler::class.java)
        val scheduledIds = mutableListOf<Int>()
        val spySchedule: (JobScheduler, android.app.job.JobInfo) -> Int = { scheduler, jobInfo ->
            scheduledIds += jobInfo.id
            scheduler.schedule(jobInfo)
        }
        val t0 = 10_000_000_000L
        val cooldown = ExpressiveUpdateScheduler.IMMEDIATE_CHECK_COOLDOWN_MILLIS

        // First cold start schedules both periodic and immediate checks.
        ExpressiveUpdateScheduler.ensureScheduled(
            context = context,
            nowMillis = t0,
            config = config,
            canNotify = true,
            scheduleJob = spySchedule,
        )
        assertThat(scheduledIds).containsExactly(
            ExpressiveUpdateScheduler.PERIODIC_JOB_ID,
            ExpressiveUpdateScheduler.IMMEDIATE_JOB_ID,
        ).inOrder()

        // Simulate immediate job finishing while periodic job stays pending; second cold start within
        // cooldown must neither reset the periodic job nor trigger another immediate network check.
        jobScheduler.cancel(ExpressiveUpdateScheduler.IMMEDIATE_JOB_ID)
        scheduledIds.clear()
        ExpressiveUpdateScheduler.ensureScheduled(
            context = context,
            nowMillis = t0 + TimeUnit.MINUTES.toMillis(5),
            config = config,
            canNotify = true,
            scheduleJob = spySchedule,
        )
        assertThat(scheduledIds).isEmpty()

        // Once cooldown elapses, immediate check may be scheduled again, while pending periodic job
        // is still untouched.
        ExpressiveUpdateScheduler.ensureScheduled(
            context = context,
            nowMillis = t0 + cooldown,
            config = config,
            canNotify = true,
            scheduleJob = spySchedule,
        )
        assertThat(scheduledIds).containsExactly(ExpressiveUpdateScheduler.IMMEDIATE_JOB_ID)

        // Switching update channels explicitly resets jobs and forces an immediate check.
        scheduledIds.clear()
        ExpressiveUpdateScheduler.onChannelChanged(
            context = context,
            nowMillis = t0 + cooldown + 60_000L,
            config = config,
            canNotify = true,
            scheduleJob = spySchedule,
        )
        assertThat(scheduledIds).containsExactly(
            ExpressiveUpdateScheduler.PERIODIC_JOB_ID,
            ExpressiveUpdateScheduler.IMMEDIATE_JOB_ID,
        ).inOrder()
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertThat(condition()).isTrue()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class FakeGitHubService(
        private val releases: List<GitHubRelease> = emptyList(),
        private val downloadBytes: ByteArray = ByteArray(0),
    ) : GitHubService {
        override suspend fun getReleases(): List<GitHubRelease> = releases

        override suspend fun getRepositoryEvents(owner: String, repo: String): List<GitHubEvent> = emptyList()

        override suspend fun getRepositoryCommits(
            owner: String,
            repo: String,
            sha: String?,
        ): List<GitHubCommit> = emptyList()

        override suspend fun compareCommits(
            owner: String,
            repo: String,
            base: String,
            head: String,
        ): GitHubCompareResponse = GitHubCompareResponse(emptyList(), 0, 0)

        override suspend fun downloadFile(fileUrl: String): ResponseBody =
            downloadBytes.toResponseBody("application/vnd.android.package-archive".toMediaType())
    }
}

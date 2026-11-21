import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import jenkins.model.Jenkins
import hudson.model.Node
import hudson.model.User
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import hudson.slaves.DumbSlave
import hudson.slaves.JNLPLauncher
import hudson.slaves.RetentionStrategy
import jenkins.security.ApiTokenProperty

def writeSecretAtomic0600 = { File target, String content ->
    def mode0600 = PosixFilePermissions.fromString('rw-------')
    def attrs = PosixFilePermissions.asFileAttribute(mode0600)
    def dir = target.parentFile.toPath()
    Files.createDirectories(dir)
    def tmp = Files.createTempFile(dir, '.' + target.name + '.', '.tmp', attrs)
    try {
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING)
        Files.setPosixFilePermissions(tmp, mode0600)
        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(tmp)
    }
}

// First-start provisioning. Inputs and generated credentials live outside Git, mode 0600.
def j = Jenkins.get()
def base = new File(System.getenv('EXPRESSIVE_CI_HOME'))
def settings = new Properties()
new File(base, 'config/bootstrap.properties').withInputStream { settings.load(it) }
def account = settings.getProperty('adminUser')
if (!(j.getSecurityRealm() instanceof HudsonPrivateSecurityRealm)) {
    j.setSecurityRealm(new HudsonPrivateSecurityRealm(false))
}
if (User.getById(account, false) == null) {
    j.getSecurityRealm().createAccount(account, settings.getProperty('adminPassword'))
}
def access = new FullControlOnceLoggedInAuthorizationStrategy()
access.setAllowAnonymousRead(false)
j.setAuthorizationStrategy(access)
j.setNumExecutors(0)
j.setSlaveAgentPort(-1)
def agentName = 'expressive-macos-android'
if (j.getNode(agentName) == null) {
    def worker = new DumbSlave(agentName, 'Dedicated local Android worker',
        new File(base, 'agent').absolutePath, '1', Node.Mode.EXCLUSIVE,
        'expressive-macos-android', new JNLPLauncher(true),
        new RetentionStrategy.Always(), [])
    j.addNode(worker)
}
def credentialFile = new File(base, 'config/jenkins-api-auth')
if (!credentialFile.exists()) {
    def user = User.getById(account, false)
    def token = user.getProperty(ApiTokenProperty.class).tokenStore.generateNewToken('Expressive CI local administration')
    user.save()
    writeSecretAtomic0600(credentialFile, account + ':' + token.plainValue)
}
def workerSecret = new File(base, 'config/agent-secret')
writeSecretAtomic0600(workerSecret, j.getNode(agentName).getComputer().getJnlpMac())
jenkins.model.JenkinsLocationConfiguration.get().setUrl('http://127.0.0.1:8091/')
j.save()

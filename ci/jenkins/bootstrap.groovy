import jenkins.model.Jenkins
import hudson.model.Node
import hudson.model.User
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import hudson.slaves.DumbSlave
import hudson.slaves.JNLPLauncher
import hudson.slaves.RetentionStrategy
import jenkins.security.ApiTokenProperty

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
    credentialFile.text = account + ':' + token.plainValue
    credentialFile.setReadable(false, false); credentialFile.setReadable(true, true)
    credentialFile.setWritable(false, false); credentialFile.setWritable(true, true)
}
def workerSecret = new File(base, 'config/agent-secret')
workerSecret.text = j.getNode(agentName).getComputer().getJnlpMac()
workerSecret.setReadable(false, false); workerSecret.setReadable(true, true)
workerSecret.setWritable(false, false); workerSecret.setWritable(true, true)
jenkins.model.JenkinsLocationConfiguration.get().setUrl('http://127.0.0.1:8091/')
j.save()

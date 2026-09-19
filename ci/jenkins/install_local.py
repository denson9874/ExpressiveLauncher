#!/usr/bin/env python3
"""Provision the existing Homebrew Jenkins LTS as a private local launchd service."""
import getpass
import os
from pathlib import Path
import plistlib
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASE = Path.home() / 'Library/Application Support/Expressive CI'
JAVA = '/opt/homebrew/opt/openjdk@21/bin/java'
WAR = '/opt/homebrew/opt/jenkins-lts/libexec/jenkins.war'
URL = 'http://127.0.0.1:8091/'


def service(label, arguments, environment):
    path = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
    path.parent.mkdir(parents=True, exist_ok=True)
    config = {'Label': label, 'ProgramArguments': arguments,
              'EnvironmentVariables': environment, 'RunAtLoad': True,
              'KeepAlive': True, 'ThrottleInterval': 10,
              'WorkingDirectory': str(BASE),
              'StandardOutPath': str(BASE / 'logs' / (label + '.out.log')),
              'StandardErrorPath': str(BASE / 'logs' / (label + '.err.log'))}
    if path.exists() and plistlib.loads(path.read_bytes()) != config:
        raise SystemExit(f'Existing service differs: {path}; review before replacing it')
    path.write_bytes(plistlib.dumps(config)); path.chmod(0o600)
    domain = f'gui/{os.getuid()}'
    if subprocess.run(['launchctl', 'print', domain + '/' + label],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        subprocess.run(['launchctl', 'bootstrap', domain, str(path)], check=True)
    print(f'{label}: configured')


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('component', choices=['controller', 'agent'])
    args = parser.parse_args()
    for name in ['', 'config', 'logs', 'jenkins/init.groovy.d', 'agent', 'bin', 'releases']:
        path = BASE / name; path.mkdir(parents=True, exist_ok=True); path.chmod(0o700)
    environment = {'PATH': '/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',
                   'JAVA_HOME': '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home',
                   'EXPRESSIVE_CI_HOME': str(BASE),
                   'JENKINS_HOME': str(BASE / 'jenkins')}
    if args.component == 'controller':
        props = BASE / 'config/bootstrap.properties'
        if not props.exists():
            props.write_text(f'adminUser={getpass.getuser()}\nadminPassword={secrets.token_urlsafe(32)}\n')
            props.chmod(0o600)
        shutil.copyfile(ROOT / 'ci/jenkins/bootstrap.groovy', BASE / 'jenkins/init.groovy.d/bootstrap.groovy')
        service('dev.expressive.jenkins', [JAVA, '-Djenkins.install.runSetupWizard=false',
                '-Djava.awt.headless=true', '-jar', WAR, '--httpListenAddress=127.0.0.1',
                '--httpPort=8091'], environment)
    else:
        import urllib.request
        urllib.request.urlretrieve(URL + 'jnlpJars/agent.jar', BASE / 'bin/agent.jar')
        if not (BASE / 'config/agent-secret').exists():
            raise SystemExit('Controller has not provisioned the agent secret yet')
        service('dev.expressive.jenkins-agent', [JAVA, '-jar', str(BASE / 'bin/agent.jar'),
                '-url', URL, '-secret', '@' + str(BASE / 'config/agent-secret'),
                '-name', 'expressive-macos-android', '-webSocket', '-workDir', str(BASE / 'agent')], environment)


if __name__ == '__main__':
    main()

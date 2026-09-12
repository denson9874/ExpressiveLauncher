#!/usr/bin/env python3
"""Administer only this loopback Jenkins through its supported HTTP API."""
import argparse
import base64
import http.cookiejar
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

BASE = Path.home() / 'Library/Application Support/Expressive CI'
ROOT = Path(__file__).resolve().parents[2]
URL = 'http://127.0.0.1:8091/'
JOBS = {'build': 'expressive-qa-build', 'publish': 'expressive-qa-publish',
        'release-build': 'expressive-release-build', 'release-publish': 'expressive-release-publish'}


class Client:
    def __init__(self):
        auth = (BASE / 'config/jenkins-api-auth').read_bytes().strip()
        self.headers = {'Authorization': 'Basic ' + base64.b64encode(auth).decode()}
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, data=None, content_type=None):
        headers = self.headers.copy()
        if content_type: headers['Content-Type'] = content_type
        request = urllib.request.Request(URL + path, data=data, headers=headers)
        return self.opener.open(request, timeout=60)

    def json(self, path):
        with self.request(path) as response: return json.load(response)


def configure(client, kind):
    job = ET.Element('flow-definition', {'plugin': 'workflow-job'})
    ET.SubElement(job, 'description').text = ('Expressive Launcher: retained signed artifacts, separate QA/stable channels; '
        'successful sealed stable builds automatically publish to the GitHub stable branch and stable update feed.')
    ET.SubElement(job, 'keepDependencies').text = 'true'
    props = ET.SubElement(job, 'properties')
    param_property = ET.SubElement(props, 'hudson.model.ParametersDefinitionProperty')
    definitions = ET.SubElement(param_property, 'parameterDefinitions')
    if kind == 'build':
        parameters = [('SOURCE_REVISION', ''), ('VERSION_NAME', '1.0.8'), ('VERSION_CODE', '9'), ('BASELINE_RELEASE_ID', '')]
    elif kind == 'release-build':
        parameters = [('VALIDATION_QA_RELEASE_ID', '')]
    else:
        parameters = [('RELEASE_ID', '')]
    for name, default in parameters:
        parameter = ET.SubElement(definitions, 'hudson.model.StringParameterDefinition')
        ET.SubElement(parameter, 'name').text = name
        ET.SubElement(parameter, 'defaultValue').text = default
        ET.SubElement(parameter, 'trim').text = 'true'
    if kind == 'publish':
        for name in ('PROMOTE_QA_FEED', 'BRIDGE_LEGACY_QA_FEED'):
            parameter = ET.SubElement(definitions, 'hudson.model.BooleanParameterDefinition')
            ET.SubElement(parameter, 'name').text = name
            ET.SubElement(parameter, 'defaultValue').text = 'false'
    definition = ET.SubElement(job, 'definition', {'class': 'org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition', 'plugin': 'workflow-cps'})
    ET.SubElement(definition, 'script').text = (ROOT / 'ci' / ('Jenkinsfile.' + kind)).read_text()
    ET.SubElement(definition, 'sandbox').text = 'true'
    ET.SubElement(job, 'disabled').text = 'false'
    xml = ET.tostring(job, encoding='utf-8', xml_declaration=True)
    try:
        client.request('job/' + JOBS[kind] + '/config.xml', xml, 'application/xml').close()
    except urllib.error.HTTPError as error:
        if error.code != 404: raise
        client.request('createItem?name=' + JOBS[kind], xml, 'application/xml').close()
    print(URL + 'job/' + JOBS[kind] + '/')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['configure', 'run', 'status', 'console'])
    parser.add_argument('--job', choices=list(JOBS))
    parser.add_argument('--revision')
    parser.add_argument('--version-name', default='1.0.8')
    parser.add_argument('--version-code', default='9')
    parser.add_argument('--release-id')
    parser.add_argument('--baseline-release-id', default='')
    parser.add_argument('--validation-qa-release-id', default='')
    parser.add_argument('--promote', action='store_true')
    parser.add_argument('--bridge-legacy-qa', action='store_true')
    parser.add_argument('--number', default='lastBuild')
    args = parser.parse_args()
    client = Client()
    if args.operation == 'configure':
        for kind in ([args.job] if args.job else JOBS): configure(client, kind)
    elif args.operation == 'run':
        import re
        args.job = args.job or 'build'
        if args.job == 'build':
            if not re.fullmatch('[0-9a-f]{40}', args.revision or ''): parser.error('Full --revision is required')
            if args.baseline_release_id and not re.fullmatch(r'qa-\d+\.\d+\.\d+-\d+-build-\d+', args.baseline_release_id): parser.error('Invalid --baseline-release-id')
            data = {'SOURCE_REVISION': args.revision, 'VERSION_NAME': args.version_name, 'VERSION_CODE': args.version_code, 'BASELINE_RELEASE_ID': args.baseline_release_id}
        elif args.job == 'release-build':
            if args.revision or args.promote or args.bridge_legacy_qa:
                parser.error('Stable build source is selected by the weekly gate')
            if args.validation_qa_release_id and not re.fullmatch(r'qa-\d+\.\d+\.\d+-[1-9]\d*-build-[1-9]\d*', args.validation_qa_release_id):
                parser.error('Invalid --validation-qa-release-id')
            data = {'VALIDATION_QA_RELEASE_ID': args.validation_qa_release_id}
        elif args.job == 'release-publish':
            if not re.fullmatch(r'release-\d+\.\d+\.\d+-[1-9]\d*-build-[1-9]\d*', args.release_id or ''):
                parser.error('Valid stable --release-id is required')
            if args.bridge_legacy_qa:
                parser.error('Stable releases cannot bridge a QA manifest')
            data = {'RELEASE_ID': args.release_id}
        else:
            if not re.fullmatch(r'qa-\d+\.\d+\.\d+-\d+-build-\d+', args.release_id or ''): parser.error('Valid --release-id is required')
            if args.bridge_legacy_qa and not args.promote: parser.error('--bridge-legacy-qa requires --promote')
            data = {'RELEASE_ID': args.release_id, 'PROMOTE_QA_FEED': str(args.promote).lower(),
                    'BRIDGE_LEGACY_QA_FEED': str(args.bridge_legacy_qa).lower()}
        # Initial parameter definitions are included explicitly by pipeline configure/start below.
        with client.request('job/' + JOBS[args.job] + '/buildWithParameters', urllib.parse.urlencode(data).encode()) as response:
            print(json.dumps({'status': response.status, 'queueUrl': response.headers.get('Location')}))
    elif args.operation == 'status':
        print(json.dumps(client.json('job/' + JOBS[args.job or 'build'] + '/' + args.number + '/api/json?tree=number,url,building,result,duration,timestamp,queueId,actions[parameters[name,value]],artifacts[fileName,relativePath]'), indent=2))
    else:
        with client.request('job/' + JOBS[args.job or 'build'] + '/' + args.number + '/consoleText') as response: print(response.read().decode())


if __name__ == '__main__':
    main()

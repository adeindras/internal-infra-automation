import xml.etree.ElementTree as ET
import os
import urllib.parse
import urllib.request
import time
import sys
import base64

if len(sys.argv) < 4:
    print("Usage: createjob.py <Maintenance Name> <Fiscal Quarter> <template_file>", file=sys.stderr)
    sys.exit(1)

maintenance_name = sys.argv[1]
fiscal_quarter = sys.argv[2]
template_file = sys.argv[3]

jenkins_basic_auth = base64.b64encode(os.environ['JENKINS_BASIC_AUTH'].encode()).decode("utf-8")

tree = ET.parse(template_file)
root = tree.getroot()

maintenance_name_encoded = urllib.parse.quote(maintenance_name)
pipeline_id = maintenance_name.lower().replace(" ", "-")

root.find("./description").text = f'Pipeline for {maintenance_name}'
root.find("./definition/scriptPath").text = f'hosting/rollout/{fiscal_quarter}/{pipeline_id}/pipeline.groovy'
root.find("./definition/scm/branches/hudson.plugins.git.BranchSpec/name").text = f'{pipeline_id}-dev'

xml_string = ET.tostring(root)

create_project_folder_request = urllib.request.Request(url=f"https://jenkinscd.accelbyte.io/job/hosting/job/Pipelines/job/Infrastructure-Rollout/job/{fiscal_quarter}/createItem?name={maintenance_name_encoded}&mode=com.cloudbees.hudson.plugins.folder.Folder&from=&json=%7B%22name%22%3A%22FolderName%22%2C%22mode%22%3A%22com.cloudbees.hudson.plugins.folder.Folder%22%2C%22from%22%3A%22%22%2C%22Submit%22%3A%22OK%22%7D&Submit=OK", method="POST")
create_project_folder_request.add_header("Content-Type", "application/x-www-form-urlencoded")
create_project_folder_request.add_header("Authorization", f"basic {jenkins_basic_auth}")
r = urllib.request.urlopen(create_project_folder_request)

time.sleep(1)

create_development_folder_request = urllib.request.Request(url=f"https://jenkinscd.accelbyte.io/job/hosting/job/Pipelines/job/Infrastructure-Rollout/job/{fiscal_quarter}/job/{maintenance_name_encoded}/createItem?name=DEVELOPMENT&mode=com.cloudbees.hudson.plugins.folder.Folder&from=&json=%7B%22name%22%3A%22FolderName%22%2C%22mode%22%3A%22com.cloudbees.hudson.plugins.folder.Folder%22%2C%22from%22%3A%22%22%2C%22Submit%22%3A%22OK%22%7D&Submit=OK", method="POST")
create_development_folder_request.add_header("Content-Type", "application/x-www-form-urlencoded")
create_development_folder_request.add_header("Authorization", f"basic {jenkins_basic_auth}")
r = urllib.request.urlopen(create_development_folder_request)

time.sleep(1)

create_job_request = urllib.request.Request(url=f"https://jenkinscd.accelbyte.io/job/hosting/job/Pipelines/job/Infrastructure-Rollout/job/{fiscal_quarter}/job/{maintenance_name_encoded}/job/DEVELOPMENT/createItem?name=main", data=xml_string, method="POST")
create_job_request.add_header("Content-Type", "application/xml")
create_job_request.add_header("Authorization", f"basic {jenkins_basic_auth}")
r = urllib.request.urlopen(create_job_request)
    
time.sleep(1)

build_job_request = urllib.request.Request(url=f"https://jenkinscd.accelbyte.io/job/hosting/job/Pipelines/job/Infrastructure-Rollout/job/{fiscal_quarter}/job/{maintenance_name_encoded}/job/DEVELOPMENT/job/main/build", method="POST")
build_job_request.add_header("Content-Type", "application/xml")
build_job_request.add_header("Authorization", f"basic {jenkins_basic_auth}")
r = urllib.request.urlopen(build_job_request)

print(f"Pipeline creation is done")
print(f"Visit https://jenkinscd.accelbyte.io/job/hosting/job/Pipelines/job/Infrastructure-Rollout/job/{fiscal_quarter}/job/{maintenance_name_encoded}/job/DEVELOPMENT/")
print(f"Pipeline code: https://bitbucket.org/accelbyte/internal-infra-automation/src/{pipeline_id}-dev/hosting/rollout/{fiscal_quarter}/{pipeline_id}")

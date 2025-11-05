#  Copyright (c) 2024 AccelByte Inc. All Rights Reserved.
#  This is licensed software from AccelByte Inc, for limitations
#  and restrictions contact your company contract manager.


import boto3
import sys
import os
import json

def main():
    environment_name = os.getenv('ENV')

    if len(sys.argv) != 3:
        print('Usage: get_latest_ecr_image.py <service_list.json> <dir>', file=sys.stderr)
        exit(1)

    service_list_filepath = sys.argv[1]
    service_dir = sys.argv[2]

    services_short_names = dict()

    with open(service_list_filepath, mode='r') as f:
        services_short_names = json.load(f)

    for long_name,short_name in services_short_names.items():
        input_file_path = f'{service_dir}/{short_name}/inputs.tfvars.json'
        if os.path.isfile(input_file_path):
            # Check if the tag exist
            latest_tag = get_latest_tag_image(long_name, environment_name)
            if not latest_tag:
                print(f"{long_name}: no matching tag found.")
                continue

            with open(input_file_path, mode='r+') as f:
                tf_input = json.load(f)
                print(f'{long_name}:{latest_tag}')
                tf_input['service_container_definition']['image'] = f'144436415367.dkr.ecr.us-west-2.amazonaws.com/{long_name}:{latest_tag}'
                f.seek(0)
                json.dump(tf_input, f, indent=2)
                f.truncate()

def get_latest_tag_image(repository_name, environment_name):
    latest_tag = ""
    get_tagged_images = []

    # Decide which tag pattern to use
    if environment_name == 'stg':
        if repository_name == 'justice-kafka-connect-sink-v2':
            tag_identifier = 'master-'
        else:
            tag_identifier = '-rc'
    else:
        tag_identifier = 'master-'

    client = boto3.client('ecr')
    paginator = client.get_paginator('describe_images')
    for page in paginator.paginate(repositoryName=repository_name):
        for image in page.get('imageDetails', []):
            # Check if the tag 'master' or 'rc' is present
            for tag in image['imageTags']:
                if tag_identifier in tag:
                    get_tagged_images.append(image)
    if get_tagged_images:
        latest_image = max(get_tagged_images, key=lambda img: img['imagePushedAt'])
        latest_tag = latest_image['imageTags'][0]

    return latest_tag

if __name__ == "__main__":
    main()
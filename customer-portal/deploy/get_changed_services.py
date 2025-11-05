#  Copyright (c) 2024 AccelByte Inc. All Rights Reserved.
#  This is licensed software from AccelByte Inc, for limitations
#  and restrictions contact your company contract manager.

import json
import os
import sys
import subprocess

def main():
    with open('config.json') as configFile:
        config = json.load(configFile)

    try:
        git_diff = subprocess.run(
            args=["git", "-C", config["iac_repo_path"], "diff", "--name-only", 'HEAD', 'HEAD~1'],
            check=True,
            capture_output=True
        )

        base_path = config["base_path"]
        changed_files = git_diff.stdout.decode("utf-8").strip().split(sep='\n')
        services = set()
        for file in changed_files:
            if file.find(f'{base_path}/') == 0:
                dirname = file.partition(base_path)[2].split('/')[1]
                if os.path.exists(f'{config["iac_repo_path"]}/{config["base_path"]}/{dirname}') and dirname != 'cluster':
                    services.add(dirname)

        for service in services:
            print(service)

    except Exception as e:
        print(e)
        sys.exit(1)



if __name__ == "__main__":
    main()

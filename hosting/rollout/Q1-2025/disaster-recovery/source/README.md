### Instalation

python3 -m venv .

source ./bin/activate

pip install -r requirements.txt

export aws_keys

python3 inventory.py -e sandbox-justice-dev -r us-east-2 -o output.json -c config.json
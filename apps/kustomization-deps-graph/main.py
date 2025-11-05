import json
import argparse
import sys


def main():
    data = json.loads(sys.stdin.read())
    print('digraph Flux {')
    print('    rankdir="LR"')
    print('    nodesep="0.5"')

    for item in data['items']:
          if 'dependsOn' in item['spec']:
              for dependency in item['spec']['dependsOn']:
                    print(f"    \"{item['metadata']['name']}\" -> \"{dependency['name']}\"")

    print('}')

if __name__ == '__main__':
    main()

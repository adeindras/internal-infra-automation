# Kustomization Dependency Graph Tool

This tool can be used to draw a directed graph of Kustomizations dependencies.

Nodes in the graph represent the Kustomization, while the directed edges points to a Kustomization that is a dependency of the Kustomizations.

## Prerequisites

- Python 3 installed in the system


## Usage

The tool accept Kustomization objects in JSON format, passed via STDIN. The output is a graph in DOT format.

For example, to render an SVG file of the Kustomization dependencies in a cluster:

```
kubectl get kustomizations -A -o json | python3 main.py | dot -Tsvg > graph.svg
```

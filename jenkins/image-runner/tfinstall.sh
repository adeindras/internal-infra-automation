#!/bin/bash
for i in $(cat ./tfversion.list); do
  tfenv install ${i}
done
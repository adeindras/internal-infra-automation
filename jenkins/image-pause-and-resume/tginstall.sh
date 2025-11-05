#!/bin/bash
for i in $(cat ./tgversion.list); do
  tgenv install ${i}
done
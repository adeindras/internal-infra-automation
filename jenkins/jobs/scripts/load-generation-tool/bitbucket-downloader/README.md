# Bitbucket Downloader

cli tools to download files from bitbucket for strike purpose

## Usage example
```bash
export BITBUCKET_ACCESS_TOKEN=<your access token>
./bitbucket-downloader -r 0779138a4a3a659e57583c224df67550f0c6f024 \
  -w accelbyte -s deployments \
  -d accelbyte/justice/development/services-overlay/justice-chat-service,accelbyte/justice/development/services-overlay/utils \
  -f accelbyte/justice/development/cluster-information.env \
  -o tmp/myoutputdir
```
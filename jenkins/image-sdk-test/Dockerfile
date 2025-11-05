FROM ubuntu:22.04

RUN apt-get update \
    && apt-get install -y --no-install-recommends xvfb unzip wget cabextract ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /etc/apt/keyrings \
    && wget -O /etc/apt/keyrings/winehq-archive.key https://dl.winehq.org/wine-builds/winehq.key  \
    && wget -NP /etc/apt/sources.list.d/ https://dl.winehq.org/wine-builds/ubuntu/dists/jammy/winehq-jammy.sources  \
    && dpkg --add-architecture i386  \
    && apt-get update  \
    && apt-get install -y --no-install-recommends winehq-stable  \
    && rm -rf /var/lib/apt/lists/*
 
# setup vars for wine
ENV DISPLAY=":100" WINEARCH="win64" WINEPREFIX="/root/.wine64" \
    WINESYSTEM32="/root/.wine64/drive_c/windows/system32" WINEDLLOVERRIDES="mscoree,mshtml=" WINEDEBUG=-all

RUN wget https://raw.githubusercontent.com/Winetricks/winetricks/master/src/winetricks \
    && chmod +x winetricks 

RUN apt-get purge cabextract -y

# To run 
# Xvfb :100 -screen 0 1280x1024x24 -nolisten tcp &
# cd /root/ags/; wine64 ./ags-test2.exe [arg]
# Use the same Amazon Linux base image
FROM public.ecr.aws/amazonlinux/amazonlinux:2023.9.20250929.0

# Combine all installation and setup commands into a single RUN instruction.
# This creates only one layer and ensures that temporary files (like downloaded
# archives and package manager cache) are not saved in the final image.
RUN dnf update -y && \
    # The base image comes with 'curl-minimal', so we don't need to install 'curl' again.
    dnf install -y tar gzip && \
    # Create directories in /opt, a standard location for optional software
    mkdir -p /opt/openjdk /opt/kafka && \
    # Download and extract OpenJDK in one step.
    # Piping 'curl' to 'tar' avoids writing the archive to the disk.
    # --strip-components=1 removes the top-level directory (e.g., jdk-11.0.2) from the archive.
    curl -sSL "https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz" \
        | tar -xz --strip-components=1 -C /opt/openjdk && \
    # Download and extract Kafka in one step.
    curl -sSL "https://downloads.apache.org/kafka/3.7.2/kafka_2.13-3.7.2.tgz" \
        | tar -xz --strip-components=1 -C /opt/kafka && \
    # Clean up the dnf cache to further reduce the image size.
    dnf clean all && \
    rm -rf /var/cache/dnf

# Set environment variables for Java and Kafka for easier use and maintenance.
# This also corrects the paths, as the original Dockerfile was likely pointing to the wrong directories.
ENV JAVA_HOME=/opt/openjdk
ENV KAFKA_HOME=/opt/kafka
ENV PATH="${KAFKA_HOME}/bin:${JAVA_HOME}/bin:${PATH}"

# (Optional) You can add a CMD or ENTRYPOINT here to run your application
# For example:
# CMD ["kafka-server-start.sh", "config/server.properties"]


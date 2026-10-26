FROM node:24-bookworm-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv ca-certificates \
    && rm -rf /var/lib/apt/lists/* && python3 -m venv /opt/qq-python
COPY requirements.txt ./
RUN /opt/qq-python/bin/pip install --no-cache-dir -r requirements.txt
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && mkdir /data && chown node:node /data
COPY src ./src
COPY vendor ./vendor
USER node
ENV DATA_DIR=/data PORT=8787 QQ_PYTHON=/opt/qq-python/bin/python
VOLUME ["/data"]
EXPOSE 8787
CMD ["node", "src/server.js"]

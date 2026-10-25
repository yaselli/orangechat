FROM node:24-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && mkdir /data && chown node:node /data
COPY src ./src
COPY vendor ./vendor
USER node
ENV DATA_DIR=/data PORT=8787
VOLUME ["/data"]
EXPOSE 8787
CMD ["node", "src/server.js"]

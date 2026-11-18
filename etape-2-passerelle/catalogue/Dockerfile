FROM node:26.12.1-alpine3.24
# Mode production : Express ne renvoie plus la pile d'appels au client
# en cas d'erreur, et npm n'installe pas les dependances de developpement
ENV NODE_ENV=production
WORKDIR /app
# Les dependances d'abord : cette couche reste en cache tant que
# package.json et package-lock.json ne changent pas
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
USER node
EXPOSE 3000
CMD ["node", "src/index.js"]

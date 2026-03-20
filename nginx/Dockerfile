FROM nginx:stable-alpine
COPY nginx/gateway.conf /etc/nginx/conf.d/default.conf
EXPOSE 8000
CMD ["nginx", "-g", "daemon off;"]

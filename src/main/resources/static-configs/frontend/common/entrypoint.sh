#!/bin/bash
echo "Starting app"

export DEV_USERINFO_ENABLED="${DEV_USERINFO_ENABLED:-off}"
export DEV_USERINFO_DEFAULT="${DEV_USERINFO_DEFAULT:-sergeyk}"

envsubst '${BASE_URL} ${URL_PREFIX} ${DEV_USERINFO_ENABLED} ${DEV_USERINFO_DEFAULT}' \
< /etc/nginx/conf.d/nginx.conf \
> /tmp/nginx.conf

mv /tmp/nginx.conf /etc/nginx/conf.d/nginx.conf

nginx -g 'daemon off;'

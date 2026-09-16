#!/bin/bash
echo "Starting app"

if [ ! -d ${JAVA_HOME} ]; then
        echo -e "\n\t${JAVA} not found. \n\tPlease set the JAVA_HOME environment variable and try again\n";
        exit 1
fi

TRUSTSTORE="${JAVA_HOME}/lib/security/cacerts"
#TRUSTSTORE="/etc/ssl/certs/java/cacerts"
PASS="changeit"
BASE_CRT_PATH="/var/lib/tls/"
CMD="${JAVA_HOME}/bin/keytool"

for cert_file in $(ls -tr $BASE_CRT_PATH/*.crt); do
    crt_alias=$(echo "public class TempUUID { public static void main(String[] args) { System.out.println(java.util.UUID.randomUUID().toString().substring(0,8)); } }" > TempUUID.java && java -cp . TempUUID.java && rm TempUUID.java) && \
    echo "Importing ${cert_file} as ${crt_alias}.."
    ${CMD} -import -v -trustcacerts -alias $crt_alias -file $cert_file -keystore ${TRUSTSTORE} -storepass ${PASS} -noprompt
done

# Sourcing env vars injected from vault
VAULT_INPUT_MOUNT_FOLDER=/app/vault
RENDERING_ENVIRONMENT_SCRIPT=${VAULT_ENVIRONMENT_FILE:-${VAULT_INPUT_MOUNT_FOLDER}/runtime_secrets.sh}
if [ -f $RENDERING_ENVIRONMENT_SCRIPT ]; then
	echo "DEBUG ::: Rendering environment from injection.."
	source $RENDERING_ENVIRONMENT_SCRIPT
fi

$@

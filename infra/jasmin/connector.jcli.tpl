# Provisioning d'UNE liaison SMPP dans Jasmin (jcli). Les variables ${...} sont substituées par provision.sh ;
# les secrets viennent de l'environnement, jamais d'un fichier versionné. Gabarit à valider sur l'environnement de test de chaque opérateur.
smppccm -a
cid smppc_${CID}
host ${LINK_HOST}
port ${SMSC_PORT}
username ${SYSTEM_ID}
password ${SMSC_PASSWORD}
systype ${SYSTEM_TYPE}
bind ${BIND_MODE}
src_ton ${SRC_TON}
src_npi ${SRC_NPI}
dst_ton ${DST_TON}
dst_npi ${DST_NPI}
elink_interval ${ELINK}
submit_throughput ${TPS}
con_fail_retry yes
con_fail_delay 10
con_loss_retry yes
con_loss_delay 10
requeue_delay 30
ok
smppccm -1 smppc_${CID}

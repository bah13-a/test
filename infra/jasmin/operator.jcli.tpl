# Provisioning d'UN opérateur dans Jasmin (jcli). Les variables ${...} sont substituées par provision.sh ;
# les secrets viennent de l'environnement, jamais d'un fichier versionné. Gabarit à valider sur l'environnement de test de chaque opérateur.
smppccm -a
cid smppc_${OPL}
host ${SMSC_HOST}
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
smppccm -1 smppc_${OPL}
user -a
username vas_${OPL}
password ${JASMIN_PASSWORD}
gid vas
uid vas_${OPL}
ok
filter -a
type UserFilter
fid vas_${OPL}_filter
uid vas_${OPL}
ok
mtrouter -a
type StaticMTRoute
order ${ORDER}
connector smppc(smppc_${OPL})
filters vas_${OPL}_filter
rate 0.0
ok
persist

# Utilisateur, filtre et route MT d'UN opérateur (les connecteurs SMPP viennent de connector.jcli.tpl, un par liaison).
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
type ${ROUTE_TYPE}
order ${ORDER}
${ROUTE_CONNECTORS}
filters vas_${OPL}_filter
rate 0.0
ok
persist

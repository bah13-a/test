# Modèle rendu au démarrage du conteneur avec les variables ALERT_* (.env). Critique -> e-mail + webhook ; avertissement -> e-mail.
global:
  smtp_smarthost: '__SMTP_HOST__'
  smtp_from: '__EMAIL_FROM__'
  smtp_auth_username: '__SMTP_USER__'
  smtp_auth_password: '__SMTP_PASSWORD__'
route:
  receiver: email
  group_by: [alertname]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    - matchers: [severity="critical"]
      receiver: critical
receivers:
  - name: email
    email_configs: [{ to: '__EMAIL_TO__', send_resolved: true }]
  - name: critical
    email_configs: [{ to: '__EMAIL_TO__', send_resolved: true }]
    webhook_configs: [{ url: '__WEBHOOK_URL__', send_resolved: true }]

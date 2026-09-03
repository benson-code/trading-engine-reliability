{{- define "payment-api.name" -}}{{ .Chart.Name }}{{- end -}}
{{- define "payment-api.fullname" -}}{{ .Release.Name }}{{- end -}}
{{- define "payment-api.labels" -}}
app.kubernetes.io/name: {{ include "payment-api.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
{{- define "payment-api.selectorLabels" -}}
app.kubernetes.io/name: {{ include "payment-api.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

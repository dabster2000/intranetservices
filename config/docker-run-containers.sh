docker stop apigateway || true && docker rm apigateway || true
docker stop contractservice || true && docker rm contractservice || true
docker stop crmservice || true && docker rm crmservice || true
docker stop financeservice || true && docker rm financeservice || true
docker stop invoiceservice || true && docker rm invoiceservice || true
docker stop marginservice || true && docker rm marginservice || true
docker stop userservice || true && docker rm userservice || true
docker stop workservice || true && docker rm workservice || true

docker run -d -p 9093:9093 --name apigateway --env-file=docker-env-apigateway.env --link jaeger -e JAEGER_AGENT_HOST="jaeger" trustworks/apigateway
docker run -d -p 9098:9098 --name contractservice --env-file=docker-env-contractservice.env --link jaeger -e JAEGER_AGENT_HOST="jaeger" trustworks/contractservice
docker run -d -p 9099:9099 --name crmservice --env-file=docker-env-crmservice.env --link jaeger -e JAEGER_AGENT_HOST="jaeger" trustworks/crmservice
docker run -d -p 9097:9097 --name financeservice --env-file=docker-env-financeservice.env --link jaeger -e JAEGER_AGENT_HOST="jaeger" trustworks/financeservice
docker run -d -p 9091:9091 --name invoiceservice --env-file=docker-env-invoiceservice.env --link jaeger -e JAEGER_AGENT_HOST="jaeger" trustworks/invoiceservice
docker run -d -p 9096:9096 --name marginservice --env-file=docker-env-marginservice.env --link jaeger -e JAEGER_AGENT_HOST="jaeger" trustworks/marginservice
docker run -d -p 9095:9095 --name userservice --env-file=docker-env-userservice.env --link jaeger -e JAEGER_AGENT_HOST="jaeger" trustworks/userservice
docker run -d -p 9094:9094 --name workservice --env-file=docker-env-workservice.env --link jaeger -e JAEGER_AGENT_HOST="jaeger" trustworks/workservice


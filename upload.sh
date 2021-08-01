#!/usr/bin/env bash

mvn clean verify || exit
sam package --template-file template.yml --output-template-file ingatlan-app-out.yml --s3-bucket ${BUCKET}
sam deploy --template-file ingatlan-app-out.yml --stack-name ingatlan-app --confirm-changeset --capabilities CAPABILITY_IAM --parameter-overrides S3Bucket=${BUCKET}
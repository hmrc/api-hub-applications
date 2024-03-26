# API Hub Backend

This service provides a backend for the API Hub specific to applications.

For more information on the project please visit this space in Confluence:
https://confluence.tools.tax.service.gov.uk/display/AH/The+API+Hub+Home

## Summary

This service will provide the following functionality:

* Register a new application
* Manage team members of an application
* Create and rotate credentials
* Add and remove scopes to credentials

## Requirements

This service is written in [Scala](http://www.scala-lang.org/) and [Play](http://playframework.com/), so needs at least a [JRE] to run.

## Dependencies
Beyond the typical HMRC Digital platform dependencies this service relies on:
- MongoDb
- Identity Management Service (IDMS)

The full set of dependencies can be started using Service Manager and the group API_HUB_ALL.

You can view service dependencies using the Tax Catalogue's Service Relationships
section here:
https://catalogue.tax.service.gov.uk/service/api-hub-applications

### MongoDb
This service uses MongoDb to store details of applications.

The MongoDb version should be 4.2 or 4.4 and is constrained by the wider platform not this service.

- Database: api-hub-applications
- Collection: applications

### IDMS
This service uses IDMS to create and retrieve client identifiers and secrets and 
also authorised scopes.

Two instances of IDMS are used: primary and secondary. The primary instance represents
the environment this service is deployed to. The secondary instance represents
the test environment for the primary environment.

IDMS is only available in production and QA. For other environments we rely on
a stubbed implementation called identity-management-service-stubs.

In production the primary IDMS is accessed via HODS Proxy and Scrubbing Centre. The 
secondary IDMS is in QA. We connect out via the Squid proxy and back in via ebridge 
to the QA environment. We cannot then connect directly out via HODS Proxy so have 
our own MDTP proxy component, identitiy-management-service-proxy. This forwards
calls to the real IDMS.

Each instance of IDMS requires a client identifier and secret to be configured
for use when authenticating calls.

See the config sections in `application.conf` for:
- `microservice.services.idms-primary`
- `microservice.services.idms-secondary`

## Using the service

### Running the application

To run the application use `sbt run` to start the service. All local dependencies should be running first.

Once everything is up and running you can access the application at

```
http://localhost:9000/api-hub-applications
```

### Authentication
The service uses internal-auth to authenticate requests using the service-to-service 
auth pattern. See https://github.com/hmrc/internal-auth

To run the service locally and make authenticated calls using tools such as curl 
or Postman the best pattern is to:
1. Start api-hub-frontend locally
2. Use the frontend to make calls to the backend
3. Use api-hub-frontend's configuration parameter internal-auth.token as follows

```
Authorization: A dummy token unique to api-hub-frontend only used when running local.
```

In deployed environments authentication and authorization are configured here:
https://github.com/hmrc/internal-auth-config

## Building the service
This service can be built on the command line using sbt.
```
sbt compile
```

### Unit tests
This microservice has many unit tests that can be run from the command line:
```
sbt test
```

### Integration tests
This microservice has some integration tests that can be run from the command line:
```
sbt it/test
```

## API Documentation
The API is documented using the [OpenAPI specification](https://swagger.io/specification/).

To see a rendered view of the documentation, install [Redocly CLI](https://redocly.com/docs/cli/installation/).
```
npm i -g @redocly/cli@latest
```

Then run from the root of the project:
```
redocly preview-docs openapi.yaml
```

Visit localhost:8080 in the browser.

## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").

# API Hub Backend

This service provides a backend for the API Hub specific to applications.

## Summary

This service will provide the following functionality:

* Register a new application
* Manage team members of an application
* Create and rotate credentials
* Add and remove scopes to credentials

## Requirements

This service is written in [Scala](http://www.scala-lang.org/) and [Play](http://playframework.com/), so needs at least a [JRE] to run.

## Run the application

To run the application use `sbt run` to start the service. All local dependencies should be running first:
* MongoDb
* The API_HUB_ALL Service Manager group

Once everything is up and running you can access the application at

```
http://localhost:9000/api-hub-applications
```

## Unit tests
```
sbt test
```

## Integration tests
```
sbt it:test
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

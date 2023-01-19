# Proposed endpoints - WIP

We should probably migrate this document to OpenAPI Specification.

## Get all applications:

`GET /applications`

Just return a small summary? Or we could return the 'full fat' version for get all?
```
[{
 "id": "<app-id>",
 "name": "some-app-name"
}]
```

## Get single application:

`GET /applications/{app-id}`

The model below is best described here:
https://docs.google.com/document/d/1_nWErDt8gFgu_rbCg2cpoaUvxelui3rOeo2s1-8eRqI/edit#heading=h.dpnzqe9tu2i1

```
{
 "id": "<app-id>",
 "name": "some-app-name",
 "teamMembers" : [{
    "id" : "123" // Do we want an ID?
    "email" : "adam@example.com"
 }]
 "environments": {
   "dev": {
     "scopes": [{  // Array for scopes, or a JSON object (id is unique)
        name: "scope-1",    // field call id? or name? or key?
        status: "APPROVED"
      }]
   },
   "qa": {
     "scopes": [
     ]
   },
   "prod": {
     "scopes": [
     ]
   }
 }
}
```

## Create an App

`POST /applications`
```
{
  name: "my app name"
}
```

## Add scope

### Draft 1

We probably want to use the Draft 2 version below as the frontend posts multiple scopes and
each can for for a different environment.

`POST /applications/{app-id}/environments/dev/scopes`
```
{
  "name": "scope-1"
}
```
(We don't need to know the status as we can infer from the environment)


### Draft 2

`POST /applications/{app-id}/scopes`
```
[{
  "environment" : "dev"
  "name": "scope-1"
},{
  ...
}]
```

## Update a scope

`PUT /applications/{app-id}/environments/dev/scopes/scope-1`
```
{
  status: "APPROVED"
}
```

## Add a team members
Be able to post many.

`POST /applications/{app-id}/teamMembers`
```
[{
  "email" : "new.person@example.com"
}]
```

## Remove a team member

### Draft 1
Is it better to not include email addresses in an endpoints URL. URLs are logged in Kibana and can therefore fall foul of not 
logging PII (Personally Identifiable Data) in Kibana. See Draft 2 for a proposal that will address this.

`DELETE /applications/{app-id}/teamMembers/{email}`

### Draft 2

`POST /applications/{app-id}/teamMembers/delete`
```
{
  "email": "person@example.com"
}
```



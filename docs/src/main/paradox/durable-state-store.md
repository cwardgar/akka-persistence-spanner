# DurableStateStore plugin

## Schema

The following tables need to be created in the configured database:

@@snip [object-tables](/target/object-tables.txt) { #object-tables } 

## Configuration

To activate the durable state store plugin, add the following line to your Akka `application.conf`:

```
akka.persistence.state.plugin = "akka.persistence.spanner.durable-state-store"
```

Shared configuration is located under `akka.persistence.spanner.durable-state-store`.

Configuration just for the durable-state-store is under `akka.persistence.spanner`. You will need to set at least:

```
akka.persistence.spanner {
    project = <spanner project>
    instance = <spanner instance>
    database = <spanner database>
}
```

Authentication details will be picked up from the environment using the standard 
mechanism described [here](https://cloud.google.com/docs/authentication/getting-started).
   
### Reference configuration 

The following can be overridden in your `application.conf`

@@snip [reference.conf](/journal/src/main/resources/reference.conf)

### Testing with the Spanner emulator

The [Spanner Emulator](https://cloud.google.com/spanner/docs/emulator). 
Set the following to connect to it:

```
akka.persistence.state.plugin = "akka.persistence.spanner.durable-state-store"
  akka.persistence.spanner {
    session-pool {
      # emulator only supports a single transaction at a time
      max-size = 1
    }
    
    use-auth = false
 }
akka.grpc.client.spanner-client {
  host = localhost
  port = 9010
  use-tls = false
}
```

You will need to have created the instance and database.

### Deletes

The durable state store supports deletes through hard deletes, which means the durable state store entries are actually deleted from the database. 
There is no materialized view with a copy of the state so if a state that is tagged is deleted it will no longer show up in changes by tag queries.

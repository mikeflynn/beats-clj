# `beats-clj`

A Clojure library to interact with the Beats Music API.
Beats Music API: [developer.beatsmusic.com/docs](https://developer.beatsmusic.com/docs)

## Installation

`beats-clj` is available as a Maven artifact from Clojars:

```clojure
  [beats-clj "0.8.0"]
```

## Usage

Require the library in your REPL:

```clojure
  (require '[beats-clj.core :as beats])
```

...or in your project.clj file

```clojure
  (ns my-app.core
    (:require [beats-clj.core :as core]))
```

Be sure to set your Beats API Application Key and Secret:

```clojure
  (beats/set-app-key! "xxxxxx")
  (beats/set-app-secret! "yyyyyyy")
```

You can see [the full documentation here](#).

## Authorization

The Beats API has both public, meaning only an API key is required, and private, meaning an authorization token from a valid Beats Music subscriber is required, endpoints. This library does not fully implement the OAuth2 handshake as it is a server-side library. To get a user's authentication token, and instead provides the API actions that accept the authentication token as an parameter.

I've included [a simple HTML file in the /resources directory](#) that will preform the OAuth2 handshake (once configured with your application credentials) and will output your authentication token. This can be used to get tokens for testing and serve as a reference point for implementation the full OAuth2 interaction in your application.

*Note: Be sure to update your Beats Application with the OAuth callback url of your test HTML file or you will get an error.*

## Help

If there are any improvements or bugs, please leave an issue on [the GitHub page](https://github.com/mikeflynn/beats-clj), reach out to [@mikeflynn_ on Twitter](http://twitter.com/mikeflynn_) or create a pull request!

## License

Copyright © 2014 Mike Flynn / [@mikeflynn_](http://twitter.com/mikeflynn_)

Distributed under the Eclipse Public License, the same as Clojure.

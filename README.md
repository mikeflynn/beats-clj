# `beats-clj`

A Clojure library to interact with the Beats Music API.
Beats Music API: [developer.beatsmusic.com/docs](https://developer.beatsmusic.com/docs)

## Installation

`beats-clj` is available as a Maven artifact from Clojars:

  [beats-clj "0.1.0"]

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

## License

Copyright © 2014 Mike Flynn / @mikeflynn_

Distributed under the Eclipse Public License, the same as Clojure.

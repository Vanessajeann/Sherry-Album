# Public test key

CI creates `sherry-test.jks` once and commits it here so successive **debug test** APKs can update the same installation.
Its passwords are the standard public Android debug value `android`, with alias `androiddebugkey`.
This key is intentionally public and is not a production/release secret. Do not use it to sign unrelated or production applications.
Configure your own private release key using `keystore/keystore.properties` for a production distribution.

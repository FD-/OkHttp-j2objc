# OkHttp-j2objc
A fork of OkHttp, modified to work with j2objc so it can be run on iOS.

# Contents
This repository contains modified versions of
 - OkHttp (3.11.0 from 2018/08/11)
 - Okio (1x branch, it seems Okio is all-Kotlin in the master branch)
 - OkHttpUrlConnection
 
 All base code was obtained from the respective project repositories about mid-August 2018. I do _not_ intend to merge new changes into this repository.

# Modifications
Most of the modifications that were needed to get it to run are related to SSL. Luckily, it's very easy to provide a custom SSLSocketFactory to OkHttp (and I used the [one I wrote based on the Secure Transport Framework](https://github.com/FD-/SSLSocketFactory-j2objc)). Still, OkHttp depends on functionality to intervene during SSL handshakes, which my SSLSocketFactory doesn't support, so I removed it from OkHttp. In particular, as the required lower-level components are not bridged into the Java layer, I had to remove support for `HostnameVerifier`, and `CertificatePinner` (from OkHttp). 

Additionally, some modifications were needed to get rid of reference cycles (which lead to memory leaks in the iOS world). 
For easier usage, I prefixed all of OkHttp's class names with `Ok` and all of Okio's class names with `Okio`.

# Functionality
The library should be fully functional, but I should note that I haven't run any test cases on it.

# Useage
This repository just contains the modified Java source code, so you'll still have to setup a j2objc build on your system to use it.

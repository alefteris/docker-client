/*-
 * -\-\-
 * docker-client
 * --
 * Copyright (C) 2019-2020 Dimitris Mandalidis
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
*/
package org.mandas.docker.client;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.client.Client;

import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.mandas.docker.client.auth.ConfigFileRegistryAuthSupplier;
import org.mandas.docker.client.auth.RegistryAuthSupplier;
import org.mandas.docker.client.npipe.NpipeConnectionSocketFactory;

public abstract class DockerClientBuilder {

  abstract Client client(URI dockerEngineUri);

  static final String UNIX_SCHEME = "unix";
  static final String NPIPE_SCHEME = "npipe";
  static final long NO_TIMEOUT = 0;
  static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = SECONDS.toMillis(5);
  static final long DEFAULT_READ_TIMEOUT_MILLIS = SECONDS.toMillis(30);
  static final int DEFAULT_CONNECTION_POOL_SIZE = 100;
  
  enum RequestProcessingMode {
    CHUNKED,
    BUFFERED;
  }
  
  public static final String ERROR_MESSAGE =
      "LOGIC ERROR: DefaultDockerClient does not support being built "
      + "with both `registryAuth` and `registryAuthSupplier`. "
      + "Please build with at most one of these options.";
  private URI uri;
  private String apiVersion;
  protected long connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
  protected long readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;
  private int connectionPoolSize = DEFAULT_CONNECTION_POOL_SIZE;
  private DockerCertificatesStore dockerCertificatesStore;
  protected boolean useProxy = true;
  private RegistryAuthSupplier registryAuthSupplier;
  private Map<String, Object> headers = new HashMap<>();
  protected RequestProcessingMode mode;
  
  public DockerClientBuilder uri(final URI uri) {
    this.uri = uri;
    return this;
  }
  
  /**
   * Set the URI for connections to Docker.
   *
   * @param uri URI String for connections to Docker
   * @return Builder
   */
  public DockerClientBuilder uri(final String uri) {
    return uri(URI.create(uri));
  }

  /**
   * Set the Docker API version that will be used in the HTTP requests to Docker daemon.
   *
   * @param apiVersion String for Docker API version
   * @return Builder
   */
  public DockerClientBuilder apiVersion(final String apiVersion) {
    this.apiVersion = apiVersion;
    return this;
  }

  /**
   * Set the timeout in milliseconds until a connection to Docker is established. A timeout value
   * of zero is interpreted as an infinite timeout.
   *
   * @param connectTimeoutMillis connection timeout to Docker daemon in milliseconds
   * @return Builder
   */
  public DockerClientBuilder connectTimeoutMillis(final long connectTimeoutMillis) {
    this.connectTimeoutMillis = connectTimeoutMillis;
    return this;
  }

  /**
   * Set the SO_TIMEOUT in milliseconds. This is the maximum period of inactivity between
   * receiving two consecutive data packets from Docker.
   *
   * @param readTimeoutMillis read timeout to Docker daemon in milliseconds
   * @return Builder
   */
  public DockerClientBuilder readTimeoutMillis(final long readTimeoutMillis) {
    this.readTimeoutMillis = readTimeoutMillis;
    return this;
  }

  /**
   * Provide certificates to secure the connection to Docker.
   *
   * @param dockerCertificatesStore DockerCertificatesStore object
   * @return Builder
   */
  public DockerClientBuilder dockerCertificates(final DockerCertificatesStore dockerCertificatesStore) {
    this.dockerCertificatesStore = dockerCertificatesStore;
    return this;
  }

  /**
   * Set the size of the connection pool for connections to Docker. Note that due to a known
   * issue, DefaultDockerClient maintains two separate connection pools, each of which is capped
   * at this size. Therefore, the maximum number of concurrent connections to Docker may be up to
   * 2 * connectionPoolSize.
   *
   * @param connectionPoolSize connection pool size
   * @return Builder
   */
  public DockerClientBuilder connectionPoolSize(final int connectionPoolSize) {
    this.connectionPoolSize = connectionPoolSize;
    return this;
  }

  /**
   * Allows connecting to Docker Daemon using HTTP proxy.
   *
   * @param useProxy tells if Docker Client has to connect to docker daemon using HTTP Proxy
   * @return Builder
   */
  public DockerClientBuilder useProxy(final boolean useProxy) {
    this.useProxy = useProxy;
    return this;
  }

  public DockerClientBuilder registryAuthSupplier(final RegistryAuthSupplier registryAuthSupplier) {
    if (this.registryAuthSupplier != null) {
      throw new IllegalStateException(ERROR_MESSAGE);
    }
    this.registryAuthSupplier = registryAuthSupplier;
    return this;
  }

  /**
   * Adds additional headers to be sent in all requests to the Docker Remote API.
   */
  public DockerClientBuilder header(String name, Object value) {
    headers.put(name, value);
    return this;
  }
  
  /**
   * Allows setting transfer encoding. CHUNKED does not send the content-length header 
   * while BUFFERED does.
   * 
   * <p>By default ApacheConnectorProvider uses CHUNKED mode. Some Docker API end-points 
   * seems to fail when no content-length is specified but a body is sent.
   * 
   * @param requestEntityProcessing is the requested entity processing to use when calling docker
   *     daemon (tcp protocol).
   * @return Builder
   */
  public DockerClientBuilder requestProcessingMode(final RequestProcessingMode mode) {
    this.mode = mode;
    return this;
  }
  
  protected HttpClientConnectionManager getConnectionManager(Registry<ConnectionSocketFactory> schemeRegistry, DockerClientBuilder builder) {
    if (builder.uri.getScheme().equals(NPIPE_SCHEME)) {
      final BasicHttpClientConnectionManager bm = new BasicHttpClientConnectionManager(schemeRegistry);
      return bm;
    }
    final PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(schemeRegistry);
    // Use all available connections instead of artificially limiting ourselves to 2 per server.
    cm.setMaxTotal(builder.connectionPoolSize);
    cm.setDefaultMaxPerRoute(cm.getMaxTotal());
    return cm;
  }

  protected Registry<ConnectionSocketFactory> getSchemeRegistry(final DockerClientBuilder builder) {
    final SSLConnectionSocketFactory https;
    if (builder.dockerCertificatesStore == null) {
      https = SSLConnectionSocketFactory.getSocketFactory();
    } else {
      https = new SSLConnectionSocketFactory(builder.dockerCertificatesStore.sslContext(),
                                             builder.dockerCertificatesStore.hostnameVerifier());
    }

    final RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder
        .<ConnectionSocketFactory>create()
        .register("https", https)
        .register("http", PlainConnectionSocketFactory.getSocketFactory());

    if (builder.uri.getScheme().equals(UNIX_SCHEME)) {
      registryBuilder.register(UNIX_SCHEME, new UnixConnectionSocketFactory(builder.uri));
    }
    
    if (builder.uri.getScheme().equals(NPIPE_SCHEME)) {
      registryBuilder.register(NPIPE_SCHEME, new NpipeConnectionSocketFactory(builder.uri));
    }

    return registryBuilder.build();
  }
  
  // TODO: missing https here
  protected ProxyConfiguration getProxyConfigurationFor(String host) {
    String proxyHost = System.getProperty("http.proxyHost");
    if (proxyHost != null) {
      String nonProxyHosts = System.getProperty("http.nonProxyHosts");
      if (nonProxyHosts != null) {
          String[] nonProxy = nonProxyHosts
            .replaceAll("^\\s*\"", "")
            .replaceAll("\\s*\"$", "")
            .split("\\|");
          for (String h: nonProxy) {
            if (host.matches(toRegExp(h))) {
              return null;
            }
          }
      }
      String proxyPort = System.getProperty("http.proxyPort");
      return ImmutableProxyConfiguration.builder()
        .proxyHost(proxyHost.replaceAll("^http://", ""))
        .proxyPort(Integer.parseInt(proxyPort))
        .proxyUser(System.getProperty("http.proxyUser"))
        .proxyPassword(System.getProperty("http.proxyPassword"))
        .build();
    }
    return null;
  }
  
  private String toRegExp(String hostnameWithWildcards) {
    return hostnameWithWildcards.replace(".", "\\.").replace("*", ".*");
  }
  
  public DefaultDockerClient build() {
    requireNonNull(uri, "uri");
    requireNonNull(uri.getScheme(), "url has null scheme");

    if ((dockerCertificatesStore != null) && !uri.getScheme().equals("https")) {
      throw new IllegalArgumentException("An HTTPS URI for DOCKER_HOST must be provided to use Docker client certificates");
    }

    URI dockerEngineUri = null;
    if (uri.getScheme().equals(UNIX_SCHEME)) {
      dockerEngineUri = UnixConnectionSocketFactory.sanitizeUri(uri);
    } else if (uri.getScheme().equals(NPIPE_SCHEME)) {
      dockerEngineUri = NpipeConnectionSocketFactory.sanitizeUri(uri);
    } else {
      dockerEngineUri = uri;
    }

    // read the docker config file for auth info if nothing else was specified
    RegistryAuthSupplier authSupplier = registryAuthSupplier;
    if (authSupplier == null) {
      authSupplier = new ConfigFileRegistryAuthSupplier();
    }
    
    Client client = client(dockerEngineUri);
    
    return new DefaultDockerClient(dockerEngineUri, apiVersion, client, authSupplier, new HashMap<>(headers));
  }
}

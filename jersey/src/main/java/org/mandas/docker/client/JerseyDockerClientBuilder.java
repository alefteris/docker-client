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

import static java.util.Arrays.asList;
import static org.mandas.docker.client.DockerHost.certPathFromEnv;
import static org.mandas.docker.client.DockerHost.configPathFromEnv;
import static org.mandas.docker.client.DockerHost.defaultAddress;
import static org.mandas.docker.client.DockerHost.defaultCertPath;
import static org.mandas.docker.client.DockerHost.defaultPort;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.NoSuchElementException;
import java.util.Optional;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.glassfish.jersey.apache.connector.ApacheClientProperties;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.mandas.docker.client.exceptions.DockerCertificateException;

public class JerseyDockerClientBuilder extends DockerClientBuilder {

  @Override
  Client client(URI dockerEngineUri) {
    Registry<ConnectionSocketFactory> schemeRegistry = getSchemeRegistry(this);
    final HttpClientConnectionManager cm = getConnectionManager(schemeRegistry, this);

    final RequestConfig requestConfig = RequestConfig.custom()
        .setConnectionRequestTimeout((int) connectTimeoutMillis)
        .setConnectTimeout((int) connectTimeoutMillis)
        .setSocketTimeout((int) readTimeoutMillis)
        .build();

    ClientConfig config = new ClientConfig(
        ObjectMapperProvider.class,
        JacksonFeature.class,
        LogsResponseReader.class,
        ProgressResponseReader.class);
    
    ProxyConfiguration proxyConfiguration = getProxyConfigurationFor(Optional.ofNullable(dockerEngineUri.getHost()).orElse("localhost"));
    if (this.useProxy && proxyConfiguration != null) {
      config.property(ClientProperties.PROXY_URI, "http://" + proxyConfiguration.proxyHost() + ":" + proxyConfiguration.proxyPort());
      config.property(ClientProperties.PROXY_USERNAME, proxyConfiguration.proxyUser());
      config.property(ClientProperties.PROXY_PASSWORD, proxyConfiguration.proxyPassword());
      //ensure Content-Length is populated before sending request via proxy.
      config.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.BUFFERED);
    }
    
    config = config
        .connectorProvider(new ApacheConnectorProvider())
        .property(ApacheClientProperties.CONNECTION_MANAGER, cm)
        .property(ApacheClientProperties.CONNECTION_MANAGER_SHARED, "true")
        .property(ApacheClientProperties.REQUEST_CONFIG, requestConfig);

    if (mode != null) {
      switch (mode) {
        case BUFFERED:
          config.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.BUFFERED);
          break;
        case CHUNKED:
          config.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.CHUNKED);
          break;
        default:
          throw new IllegalStateException("Invalid request processing mode " + mode);
      }
    }

    return ClientBuilder.newBuilder()
        .withConfig(config)
        .build();
  }
  
  /**
   * Create a new {@link DefaultDockerClient} builder.
   *
   * @return Returns a builder that can be used to further customize and then build the client.
   */
  public static DockerClientBuilder builder() {
    return new JerseyDockerClientBuilder();
  }

  /**
   * Create a new {@link DefaultDockerClient} builder prepopulated with values loaded from the
   * DOCKER_HOST and DOCKER_CERT_PATH environment variables.
   *
   * @return Returns a builder that can be used to further customize and then build the client.
   * @throws DockerCertificateException if we could not build a DockerCertificates object
   */
  public static DockerClientBuilder fromEnv() throws DockerCertificateException {
    final String endpoint = DockerHost.endpointFromEnv();
    final Path dockerCertPath = Paths.get(asList(certPathFromEnv(), configPathFromEnv(), defaultCertPath())
    	.stream()
    	.filter(cert -> cert != null)
    	.findFirst()
    	.orElseThrow(() -> new NoSuchElementException("Cannot find docker certificated path")));
  
    final JerseyDockerClientBuilder builder = new JerseyDockerClientBuilder();
  
    final Optional<DockerCertificatesStore> certs = DockerCertificates.builder()
        .dockerCertPath(dockerCertPath).build();
  
    if (endpoint.startsWith(UNIX_SCHEME + "://")) {
      builder.uri(endpoint);
    } else if (endpoint.startsWith(NPIPE_SCHEME + "://")) {
      builder.uri(endpoint);
    } else {
      final String stripped = endpoint.replaceAll(".*://", "");
      final String scheme = certs.isPresent() ? "https" : "http";
      URI initialUri = URI.create(scheme + "://" + stripped);
      if (initialUri.getPort() == -1 && initialUri.getHost() == null) {
    	  initialUri = URI.create(scheme + "://" + defaultAddress() + ":" + defaultPort());
      } else if (initialUri.getHost() == null) {
    	  initialUri = URI.create(scheme + "://" + defaultAddress()+ ":" + initialUri.getPort());
      } else if (initialUri.getPort() == -1) {
    	  initialUri = URI.create(scheme + "://" + initialUri.getHost() + ":" + defaultPort());
      }
      builder.uri(initialUri);
    }
  
    if (certs.isPresent()) {
      builder.dockerCertificates(certs.get());
    }
  
    return builder;
  }

  /**
   * Create a new client with default configuration.
   *
   * @param uri The docker rest api uri.
   */
  public static DockerClient newClient(final String uri) {
    return newClient(URI.create(uri.replaceAll("^unix:///", "unix://localhost/")));
  }

  /**
   * Create a new client with default configuration.
   *
   * @param uri The docker rest api uri.
   */
  public static DockerClient newClient(final URI uri) {
    return new JerseyDockerClientBuilder().uri(uri).build();
  }

  /**
   * Create a new client with default configuration.
   *
   * @param uri                The docker rest api uri.
   * @param dockerCertificatesStore The certificates to use for HTTPS.
   */
  public static DockerClient newClient(final URI uri, final DockerCertificatesStore dockerCertificatesStore) {
    return new JerseyDockerClientBuilder().uri(uri).dockerCertificates(dockerCertificatesStore).build();
  }
}
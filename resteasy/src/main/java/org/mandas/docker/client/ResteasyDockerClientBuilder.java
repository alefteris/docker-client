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
import java.util.concurrent.TimeUnit;

import javax.ws.rs.client.Client;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.config.Registry;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient43Engine;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.mandas.docker.client.exceptions.DockerCertificateException;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

public class ResteasyDockerClientBuilder extends DockerClientBuilder {

  @Override
  Client client(URI dockerEngineUri) {
    Registry<ConnectionSocketFactory> schemeRegistry = getSchemeRegistry(this);
    final HttpClientConnectionManager cm = getConnectionManager(schemeRegistry, this);
    HttpClientBuilder clientBuilder = HttpClients.custom().setConnectionManager(cm);
    ProxyConfiguration proxyConfiguration = getProxyConfigurationFor(Optional.ofNullable(dockerEngineUri.getHost()).orElse("localhost"));
    if (this.useProxy && proxyConfiguration != null) {
      Credentials credentials = new UsernamePasswordCredentials(proxyConfiguration.proxyUser(), proxyConfiguration.proxyPassword());
      CredentialsProvider credProvider = new BasicCredentialsProvider();
      credProvider.setCredentials(new AuthScope(proxyConfiguration.proxyHost(), proxyConfiguration.proxyPort()), credentials);
      clientBuilder
        .setProxy(new HttpHost(proxyConfiguration.proxyHost(), proxyConfiguration.proxyPort()))
        .setDefaultCredentialsProvider(credProvider)
        .setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy());
    }
    
    CloseableHttpClient httpClient = clientBuilder.build();
    ApacheHttpClient43Engine engine = new ApacheHttpClient43Engine(httpClient);

    return new ResteasyClientBuilderImpl()
      .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
      .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
      .connectionCheckoutTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
      .httpEngine(engine)
      .register(ObjectMapperProvider.class)
      .register(LogsResponseReader.class)
      .register(ProgressResponseReader.class)
      .register(JacksonJsonProvider.class)
      .build();
  }
  
  /**
   * Create a new {@link DefaultDockerClient} builder.
   *
   * @return Returns a builder that can be used to further customize and then build the client.
   */
  public static ResteasyDockerClientBuilder builder() {
    return new ResteasyDockerClientBuilder();
  }

  /**
   * Create a new {@link DefaultDockerClient} builder prepopulated with values loaded from the
   * DOCKER_HOST and DOCKER_CERT_PATH environment variables.
   *
   * @return Returns a builder that can be used to further customize and then build the client.
   * @throws DockerCertificateException if we could not build a DockerCertificates object
   */
  public static ResteasyDockerClientBuilder fromEnv() throws DockerCertificateException {
    final String endpoint = DockerHost.endpointFromEnv();
    final Path dockerCertPath = Paths.get(asList(certPathFromEnv(), configPathFromEnv(), defaultCertPath())
    	.stream()
    	.filter(cert -> cert != null)
    	.findFirst()
    	.orElseThrow(() -> new NoSuchElementException("Cannot find docker certificated path")));
  
    final ResteasyDockerClientBuilder builder = new ResteasyDockerClientBuilder();
  
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
    return new ResteasyDockerClientBuilder().uri(uri).build();
  }

  /**
   * Create a new client with default configuration.
   *
   * @param uri                The docker rest api uri.
   * @param dockerCertificatesStore The certificates to use for HTTPS.
   */
  public static DockerClient newClient(final URI uri, final DockerCertificatesStore dockerCertificatesStore) {
    return new ResteasyDockerClientBuilder().uri(uri).dockerCertificates(dockerCertificatesStore).build();
  }
}
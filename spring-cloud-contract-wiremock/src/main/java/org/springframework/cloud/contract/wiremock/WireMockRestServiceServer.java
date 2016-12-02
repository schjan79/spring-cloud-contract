/*
 * Copyright 2015 the original author or authors.
 *
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
 */

package org.springframework.cloud.contract.wiremock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.springframework.test.util.AssertionErrors.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.*;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.util.AssertionErrors;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.MockRestServiceServer.MockRestServiceServerBuilder;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.MultiValue;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.matching.MultiValuePattern;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Convenience class for loading WireMock stubs into a {@link MockRestServiceServer}. In
 * this way using a {@link RestTemplate} can mock the responses from a server using
 * WireMock JSON DSL instead of the native Java DSL.
 *
 * @author Dave Syer
 *
 */
public class WireMockRestServiceServer {

	private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

	private String suffix = ".json";

	private String baseUrl = "";

	private MockRestServiceServerBuilder builder;

	private List<String> locations = new ArrayList<String>();

	private List<String> files = new ArrayList<String>();

	private boolean ignoreExpectOrder = true;

	private WireMockRestServiceServer(RestTemplate restTemplate) {
		this.builder = MockRestServiceServer.bindTo(restTemplate);
	}

	/**
	 * Public factory method for wrapping a rest template into a MockRestServiceServer.
	 *
	 * @param restTemplate the rest template to wrap
	 * @return a WireMockRestServiceServer
	 */
	public static WireMockRestServiceServer with(RestTemplate restTemplate) {
		return new WireMockRestServiceServer(restTemplate);
	}

	/**
	 * Flag to tell the MockRestServiceServer to ignore the order of calls when matching
	 * requests. The default is true because there is an implied ordering in the stubs (by
	 * url path and with more specific request matchers first).
	 *
	 * @param ignoreExpectOrder flag value (default true)
	 * @return this
	 */
	public WireMockRestServiceServer ignoreExpectOrder(boolean ignoreExpectOrder) {
		this.ignoreExpectOrder = ignoreExpectOrder;
		return this;
	}

	/**
	 * If stub locations are given as a directory, then we search recursively in that
	 * directory for files with this suffix. Default is ".json".
	 *
	 * @param suffix the suffix to use when creating a resource pattern
	 * @return this
	 */
	public WireMockRestServiceServer suffix(String suffix) {
		this.suffix = suffix;
		return this;
	}

	/**
	 * Add a base url to all requests. Most WireMock JSON stubs have a path, but no
	 * protocol or host in the request matcher, so this is useful when your rest template
	 * is calling to a specific host.
	 *
	 * @param baseUrl a base url to apply
	 * @return this
	 */
	public WireMockRestServiceServer baseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
		return this;
	}

	/**
	 * Add some resource locations for stubs. Each location can be a resource path (to a
	 * single JSON file), or a pattern with ant-style wildcards to load all stubs that
	 * match, or a plain directory name (which will have
	 * <code>&#42;&#42;/&#42;.json</code> appended, where ".json" is the value of the
	 * {@link #suffix(String) suffix}). Examples:
	 *
	 * <pre>
	 * classpath:/mappings/foo.json
	 * classpath:/mappings/*.json
	 * classpath:META-INF/com.example/stubs/1.0.0/mappings/&#42;&#42;/&#42;.json
	 * file:src/test/resources/stubs
	 * </pre>
	 *
	 * @param locations a set of resource locations
	 * @return this
	 */
	public WireMockRestServiceServer stubs(String... locations) {
		this.locations.addAll(Arrays.asList(locations));
		return this;
	}

	/**
	 * Add some resource locations for files that represent response bodies. Wiremock
	 * defaults to "file:src/test/resources/__files".
	 *
	 * @param locations
	 * @return this
	 */
	public WireMockRestServiceServer files(String... locations) {
		this.files.addAll(Arrays.asList(locations));
		return this;
	}

	/**
	 * Build a MockRestServiceServer from the configured stubs. The server can later be
	 * verified (optionally), if you need to check that all expected requests were made.
	 *
	 * @return a MockRestServiceServer
	 */
	public MockRestServiceServer build() {
		if (this.ignoreExpectOrder) {
			this.builder.ignoreExpectOrder(true); // default is false
		}
		MockRestServiceServer server = this.builder.build();
		List<StubMapping> mappings = new ArrayList<>();
		for (String location : this.locations) {
			try {
				for (Resource resource : this.resolver.getResources(pattern(location))) {
					StubMapping mapping = mapping(resource);
					mappings.add(mapping);
				}
			}
			catch (IOException e) {
				throw new IllegalStateException("Cannot load resources for: " + location, e);
			}
		}
		if (this.ignoreExpectOrder) {
			Collections.sort(mappings, new StubMappingComparator());
		}
		for (StubMapping mapping : mappings) {
			RequestPattern expectedRequest = mapping.getRequest();
			ResponseActions expect = server.expect(
					null == expectedRequest.getUrlPath()
					? requestTo(request(expectedRequest))
					: requestToUrlPath(expectedRequest.getUrlPath())
			);
			expect.andExpect(method(HttpMethod.valueOf(expectedRequest.getMethod().getName().toUpperCase())));
			requestHeaders(expect, expectedRequest);
			requestQueryParameters(expect, expectedRequest);
			expect.andRespond(response(mapping.getResponse()));
		}
		return server;
	}

	private RequestMatcher requestToUrlPath(final String urlPath) {
		final String expectedUrlPath = this.baseUrl + urlPath;
		return new RequestMatcher() {
			@Override
			public void match(ClientHttpRequest actualRequest) throws IOException, AssertionError {
				String actualUrlPath = actualRequest.getURI().toString().split("\\?")[0];
				AssertionErrors.assertEquals("Request URL path", expectedUrlPath, actualUrlPath);
			}
		};
	}

	private String request(RequestPattern request) {
		return this.baseUrl + (request.getUrl() == null ? "/" : request.getUrl());
	}

	private String pattern(String location) {
		if (!StringUtils.getFilename(location).contains(".") && !location.contains("*")) {
			if (!location.endsWith("/")) {
				location = location + "/";
			}
			location = location + "**/*" + this.suffix;
		}
		return location;
	}

	private StubMapping mapping(Resource resource) throws IOException {
		return Json.read(StreamUtils.copyToString(resource.getInputStream(), Charset.defaultCharset()),
				StubMapping.class);
	}

	private DefaultResponseCreator response(ResponseDefinition response) {
		return withStatus(HttpStatus.valueOf(response.getStatus())).body(body(response))
				.contentType(contentType(response)).headers(responseHeaders(response));
	}

	private String body(ResponseDefinition response) {
		if (response.getBody() != null) {
			return response.getBody();
		}
		String file = response.getBodyFileName();
		if (file != null) {
			List<String> locations = this.files.isEmpty() ? Arrays.asList("classpath:/__files/") : this.files;
			for (String location : locations) {
				try {
					if (!location.endsWith("/")) {
						location = location + "/";
					}
					for (Resource files : this.resolver.getResources(location)) {
						if (files.exists()) {
							try {
								Resource resource = files.createRelative(file);
								if (resource.exists()) {
									return StreamUtils.copyToString(resource.getInputStream(),
											Charset.forName("UTF-8"));
								}
							}
							catch (IOException e) {
								throw new IllegalStateException("Cannot locate body file: " + file, e);
							}
						}
					}
				}
				catch (IOException e) {
					// Ignore
				}
			}
		}
		return "";
	}

	private void requestHeaders(ResponseActions expect, RequestPattern request) {
		if (request.getHeaders() != null) {
			for (final String header : request.getHeaders().keySet()) {
				final MultiValuePattern pattern = request.getHeaders().get(header);
				expect.andExpect(header(header, new BaseMatcher<String>() {


					@Override
					public boolean matches(Object item) {
						return pattern.match(new MultiValue(header, Arrays.asList((String) item))).isExactMatch();
					}

					@Override
					public void describeTo(Description description) {
						description.appendText("should match header: " + header + " with ")
								.appendText(pattern.getExpected());
					}
				}));
			}
		}
	}

    private void requestQueryParameters(ResponseActions expect, RequestPattern request) {
	    if (null != request.getQueryParameters()) {
	        for (final String queryParameter : request.getQueryParameters().keySet()) {
	            final MultiValuePattern pattern = request.getQueryParameters().get(queryParameter);
	            expect.andExpect(queryParameter(queryParameter, new BaseMatcher<String>() {
                    @Override
                    public boolean matches(Object item) {
                        return pattern.match(new MultiValue(queryParameter, Arrays.asList((String) item))).isExactMatch();
                    }

                    @Override
                    public void describeTo(Description description) {
                        description.appendText("should match query parameter: " + queryParameter + " with ")
                                .appendText(pattern.getExpected());
                    }
                }));
            }
        }
    }

    /**
     * Assert request query parameter values with the given Hamcrest matcher.
     */
    @SafeVarargs
    public static RequestMatcher queryParameter(final String name, final Matcher<? super String>... matchers) {
        return new RequestMatcher() {
            @Override
            public void match(ClientHttpRequest request) {
                try {
                    String decodeUri = UriUtils.decode(request.getURI().toString(), "UTF-8");
                    MultiValueMap<String, String> queryParameters = UriComponentsBuilder.fromUriString(decodeUri).build().getQueryParams();
                    assertQueryParameterValueCount(name, queryParameters, matchers.length);
                    for (int i = 0 ; i < matchers.length; i++) {
                        assertThat("Request query parameter", queryParameters.get(name).get(i), matchers[i]);
                    }
                } catch (UnsupportedEncodingException e) {
                    throw new AssertionError("Failed to decode URI: " + request.getURI(), e);
                }
            }
        };
    }

    private static void assertQueryParameterValueCount(final String name, MultiValueMap<String, String> queryParameters, int expectedCount) {
        List<String> actualValues = queryParameters.get(name);
        AssertionErrors.assertTrue("Expected query parameter <" + name + ">", actualValues != null);
        AssertionErrors.assertTrue("Expected query parameter <" + name + "> to have at least <" + expectedCount +
                "> values but found " + actualValues, expectedCount <= actualValues.size());
    }

    private HttpHeaders responseHeaders(ResponseDefinition response) {
		HttpHeaders headers = new HttpHeaders();
		if (response.getHeaders() != null) {
			for (HttpHeader header : response.getHeaders().all()) {
				if (!header.keyEquals("Content-Type")) {
					for (String value : header.values()) {
						headers.add(header.key(), value);
					}
				}
			}
		}
		return headers;
	}

	private MediaType contentType(ResponseDefinition response) {
		String value = null;
		if (response.getHeaders() != null) {
			HttpHeader header = response.getHeaders().getHeader("Content-Type");
			if (header != null && header.isPresent()) {
				value = header.firstValue();
			}
		}
		return value == null ? MediaType.TEXT_PLAIN : MediaType.valueOf(value);
	}

	private static class StubMappingComparator implements Comparator<StubMapping> {

		@Override
		public int compare(StubMapping one, StubMapping two) {
			if (one == two) {
				return 0;
			}
			int value = request(one.getRequest()).compareTo(request(two.getRequest()));
			if (value == 0) {

				if (one.getPriority() != null) {
					if (two.getPriority() != null) {
						return one.getPriority().compareTo(two.getPriority());
					}
					else {
						return -one.getPriority();
					}
				}
				if (two.getPriority() != null) {
					return -two.getPriority();
				}

				// Every mapping has a url pattern, and zero or more header patterns
				int twos = 0;
				if (two.getRequest().getHeaders() != null) {
					twos = two.getRequest().getHeaders().size();
				}
				int ones = 0;
				if (one.getRequest().getHeaders() != null) {
					ones = one.getRequest().getHeaders().size();
				}
				value = twos - ones;
				if (value == 0) {
					// Same number of header matchers
					if (two.getPriority() != null) {
						return one.getPriority() != null ? one.getPriority() - two.getPriority() : 1;
					}
					value = (int) (one.getInsertionIndex() - two.getInsertionIndex());
				}
			}
			return value;
		}

		private String request(RequestPattern request) {
			return (request.getUrlPath() == null ? (request.getUrl() == null ? "/" : request.getUrl())
					: request.getUrlPath());
		}

	}

}

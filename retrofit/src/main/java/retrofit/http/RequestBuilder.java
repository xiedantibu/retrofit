// Copyright 2012 Square, Inc.
package retrofit.http;

import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import retrofit.http.client.Request;
import retrofit.io.TypedBytes;

import static retrofit.http.RestAdapter.UTF_8;
import static retrofit.http.RestMethodInfo.NO_SINGLE_ENTITY;

/**
 * Builds HTTP requests from Java method invocations.  Handles "path parameters" in the
 * {@code apiUrl} in the form of "path/to/url/{id}/action" where a parameter annotated with
 * {@code @Named("id")} is inserted into the url.  Note that this replacement can be recursive if:
 * <ol>
 * <li>Multiple sets of brackets are nested ("path/to/{{key}a}.</li>
 * <li>The order of {@link javax.inject.Named @Named} values go from innermost to outermost.</li>
 * <li>The values replaced correspond to {@link javax.inject.Named @Named} parameters.</li>
 * </ol>
 */
final class RequestBuilder {
  private final Converter converter;

  private RestMethodInfo methodInfo;
  private Object[] args;
  private String apiUrl;
  private List<Header> headers;

  RequestBuilder(Converter converter) {
    this.converter = converter;
  }

  RequestBuilder setMethodInfo(RestMethodInfo methodDetails) {
    this.methodInfo = methodDetails;
    return this;
  }

  RequestBuilder setApiUrl(String apiUrl) {
    this.apiUrl = apiUrl;
    return this;
  }

  RequestBuilder setArgs(Object[] args) {
    this.args = args;
    return this;
  }

  RequestBuilder setHeaders(List<Header> headers) {
    this.headers = headers;
    return this;
  }

  /** List of all URL parameters. Return value will be mutated. */
  private List<Parameter> createParamList() {
    List<Parameter> params = new ArrayList<Parameter>();

    // Add query parameter(s), if specified.
    for (QueryParam annotation : methodInfo.pathQueryParams) {
      params.add(new Parameter(annotation.name(), String.class, annotation.value()));
    }

    // Add arguments as parameters.
    String[] pathNamedParams = methodInfo.namedParams;
    int singleEntityArgumentIndex = methodInfo.singleEntityArgumentIndex;
    for (int i = 0; i < pathNamedParams.length; i++) {
      Object arg = args[i];
      if (arg == null) continue;
      if (i != singleEntityArgumentIndex) {
        params.add(new Parameter(pathNamedParams[i], arg.getClass(), arg));
      }
    }

    return params;
  }

  Request build() throws URISyntaxException {
    // Alter parameter list if path parameters are present.
    Set<String> pathParams = new LinkedHashSet<String>(methodInfo.pathParams);
    List<Parameter> paramList = createParamList();
    String replacedPath = methodInfo.path;
    if (!pathParams.isEmpty()) {
      for (String pathParam : pathParams) {
        Parameter found = null;
        for (Parameter param : paramList) {
          if (param.getName().equals(pathParam)) {
            found = param;
            break;
          }
        }
        if (found != null) {
          String value;
          try {
            value = URLEncoder.encode(String.valueOf(found.getValue()), UTF_8);
          } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
          }
          replacedPath = replacedPath.replace("{" + found.getName() + "}", value);
          paramList.remove(found);
        } else {
          throw new IllegalArgumentException(
              "URL param " + pathParam + " has no matching method @Named param.");
        }
      }
    }

    if (methodInfo.singleEntityArgumentIndex != NO_SINGLE_ENTITY) {
      // We're passing a JSON object as the entity: paramList should only contain path param values.
      if (!paramList.isEmpty()) {
        throw new IllegalStateException(
            "Found @Named param on single-entity request that was not used for path substitution.");
      }
    }

    StringBuilder url = new StringBuilder(apiUrl);
    if (!apiUrl.endsWith("/")) {
      url.append("/");
    }
    if (replacedPath != null) {
      url.append(replacedPath);
    }

    TypedBytes body = null;
    Map<String, TypedBytes> bodyParameters = new LinkedHashMap<String, TypedBytes>();
    if (!methodInfo.restMethod.hasBody()) {
      if (!paramList.isEmpty()) {
        url.append("?");
        for (int i = 0, count = paramList.size(); i < count; i++) {
          Parameter nonPathParam = paramList.get(i);
          url.append(nonPathParam.getName()).append("=").append(nonPathParam.getValue());
          if (i > 0 && i < count - 1) {
            url.append("&");
          }
        }
      }
    } else if (!paramList.isEmpty()) {
      body = converter.fromParams(paramList);
    } else if (methodInfo.singleEntityArgumentIndex != NO_SINGLE_ENTITY) {
      Object singleEntity = args[methodInfo.singleEntityArgumentIndex];
      if (singleEntity instanceof TypedBytes) {
        body = (TypedBytes) singleEntity;
      } else {
        body = converter.fromObject(singleEntity);
      }
    }

    return new Request(methodInfo.restMethod.value(), url.toString(), headers, body, null);
  }
}
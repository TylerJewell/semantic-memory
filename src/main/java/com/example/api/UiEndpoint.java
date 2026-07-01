package com.example.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.http.HttpResponses;

/** Serves the single-page UI from {@code src/main/resources/static-resources/index.html}. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/")
public class UiEndpoint {

  @Get("/")
  public HttpResponse index() {
    return HttpResponses.staticResource("index.html");
  }
}

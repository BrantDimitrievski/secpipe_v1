```mermaid
classDiagram
class AnthropicMessagesClient {
  ~String API_VERSION
  -String JSON_RULE
  -JsonMapper MAPPER
  -Duration CONNECT_TIMEOUT
  -Duration REQUEST_TIMEOUT
  -URI endpoint
  -Map env
  -HttpClient http
  ~key(Map env) Optional
  +complete(String modelId, LlmClient request) RawCompletion
  -body(String modelId, LlmClient r) String
  -parse(HttpResponse response) RawCompletion
  -retryAfter(HttpResponse response) Optional
}
class AnthropicProviderFactory {
  ~String DEFAULT_BASE_URL
  ~Map DEFAULTS
  -defaults(String strong, String standard) Map
  +mode() ProviderMode
  +defaultModels() Map
  +probe(SecPipeConfig config, Map env) Optional
  +create(SecPipeConfig config, Map env) ProviderClient
}
```

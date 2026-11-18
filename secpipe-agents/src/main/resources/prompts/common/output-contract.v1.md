<!--
  Shared output-rules fragment for every SecPipe LLM agent (WO-G.1). Variable: schema_id.
  This comment is stripped before use.
-->
Output rules:
- Reply with exactly one JSON object that validates against the schema `{{schema_id}}`.
- Use the property names and enum spellings of that schema exactly; add no other properties.
- Start your reply with `{` and end it with `}`. Nothing before or after it.

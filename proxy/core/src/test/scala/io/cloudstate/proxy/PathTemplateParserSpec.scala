/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.proxy

import io.cloudstate.proxy.PathTemplateParser.{PathTemplateParseException, TemplateVariable}
import org.scalatest.{Matchers, WordSpec}

class PathTemplateParserSpec extends WordSpec with Matchers {

  private def matches(template: PathTemplateParser.ParsedTemplate, path: String): Option[List[String]] = {
    val m = template.regex.pattern.matcher(path)
    if (m.matches()) {
      Some((1 to m.groupCount()).map(m.group).toList)
    } else None
  }

  private def failParse(path: String) = {
    val e = the[PathTemplateParseException] thrownBy PathTemplateParser.parse(path)
    // Uncomment to sanity check the user friendliness of error messages
    // println(e.prettyPrint + "\n")
    e
  }

  "The path template parse" should {
    "parse a simple path template" in {
      val template = PathTemplateParser.parse("/foo")
      template.fields shouldBe empty
      matches(template, "/foo") shouldBe Some(Nil)
      matches(template, "/bar") shouldBe None
    }

    "parse a path template with variables" in {
      val template = PathTemplateParser.parse("/foo/{bar}/baz")
      template.fields shouldBe List(TemplateVariable(List("bar"), false))
      matches(template, "/foo") shouldBe None
      matches(template, "/foo/bl/ah/baz") shouldBe None
      matches(template, "/foo/blah/baz") shouldBe Some(List("blah"))
    }

    "parse a path template with multiple variables" in {
      val template = PathTemplateParser.parse("/foo/{bar}/baz/{other}")
      template.fields shouldBe List(TemplateVariable(List("bar"), false), TemplateVariable(List("other"), false))
      matches(template, "/foo/blah/baz") shouldBe None
      matches(template, "/foo/blah/baz/blah2") shouldBe Some(List("blah", "blah2"))
    }

    "support variable templates" in {
      val template = PathTemplateParser.parse("/foo/{bar=*/a/*}/baz")
      template.fields shouldBe List(TemplateVariable(List("bar"), true))
      matches(template, "/foo/blah/baz") shouldBe None
      matches(template, "/foo/bl/a/h/baz") shouldBe Some(List("bl/a/h"))
    }

    "support rest of path glob matching" in {
      val template = PathTemplateParser.parse("/foo/{bar=**}")
      template.fields shouldBe List(TemplateVariable(List("bar"), true))
      matches(template, "/foo/blah/baz") shouldBe Some(List("blah/baz"))
    }

    "support verbs" in {
      val template = PathTemplateParser.parse("/foo/{bar}:watch")
      template.fields shouldBe List(TemplateVariable(List("bar"), false))
      matches(template, "/foo/blah") shouldBe None
      matches(template, "/foo/blah:watch") shouldBe Some(List("blah"))
    }

    "fail to parse nested variables" in {
      val e = failParse("/foo/{bar={baz}}")
      e.column shouldBe 11
    }

    "fail to parse templates that don't start with a slash" in {
      val e = failParse("foo")
      e.column shouldBe 1
    }

    "fail to parse templates with missing equals" in {
      val e = failParse("/foo/{bar*}")
      e.column shouldBe 10
    }

    "fail to parse templates with no closing bracket" in {
      val e = failParse("/foo/{bar")
      e.column shouldBe 10
    }

    "fail to parse templates with badly placed stars" in {
      val e = failParse("/f*o")
      e.column shouldBe 3
    }

    "fail to parse templates with bad variable patterns" in {
      val e = failParse("/foo/{bar=ba*}")
      e.column shouldBe 13
    }
  }

}

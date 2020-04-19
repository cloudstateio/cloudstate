package io.cloudstate.proxy

import java.io.File
import java.lang.System.{lineSeparator => EOL}
import java.nio.file.Files

import scala.collection.JavaConverters._
import scala.reflect.ensureAccessible
import com.typesafe.config._, impl.Parseable

object HoconFormat {
  def reformat(f: File) = Files.writeString(f.toPath, withEol(render(parseValue(parseable(f)))))

  def render(configValue: ConfigValue): String = {
    val sb = new StringBuilder()
    var indent = -1

    def loop(cv: ConfigValue): Unit = cv match {
      case xs: ConfigList =>
        appendRepeat('[', xs.asScala)(v => v)(loop)
      case kvs: ConfigObject =>
        val outer = if (sb.isEmpty && !kvs.isEmpty) (0: Char) else '{'
        appendRepeat(outer, pairsSorted(kvs))(_._2) { kv =>
          sb.append(renderString(kv._1)).append(": ")
          loop(kv._2)
        }
      case x =>
        x.unwrapped() match {
          case s: String => sb.append(ConfigUtil.quoteString(s))
          case v => sb.append(v)
        }
    }

    def appendRepeat[A](outer: Char, xs: Seq[A])(toVal: A => ConfigValue)(each: A => Unit) = {
      val formatted = xs.nonEmpty && (indent <= 0 || (indent == 1 && xs.size > 1))
      def nl() = if (formatted) sb.append(EOL + "  " * indent)

      if (outer != 0) {
        sb.append(outer)
        indent += 1
        nl()
      }

      var first = true
      for (x <- xs) {
        if (first) first = false
        else if (formatted) nl()
        else sb.append(", ")

        for (comment <- toVal(x).origin.comments.asScala) {
          sb.append('#')
          if (!(comment.startsWith(" "))) sb.append(' ')
          sb.append(comment)
          nl()
        }

        each(x)
      }

      if (outer != 0) {
        indent -= 1
        if (!first) nl()
        sb.append((outer + 2).toChar)
      }
    }

    loop(configValue)
    sb.toString
  }

  private val keysOrder =
    "name" ::
    "allDeclaredFields" :: "allDeclaredMethods" :: "allDeclaredConstructors" :: "allDeclaredClasses" ::
    "allPublicFields" :: "allPublicMethods" :: "allPublicConstructors" :: "allPublicClasses" ::
    "fields" :: "methods" ::
    Nil

  private def pairsSorted(obj: ConfigObject) =
    obj.keySet.asScala.toSeq
      .sorted { (a: String, b: String) =>
        val aDigits = a.nonEmpty && a.forall(Character.isDigit(_))
        val bDigits = b.nonEmpty && b.forall(Character.isDigit(_))
        val aKey = keysOrder.contains(a)
        val bKey = keysOrder.contains(b)
        if (aDigits && bDigits)
          new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b))
        else if (aDigits) -1
        else if (bDigits) 1
        else if (aKey && bKey) keysOrder.indexOf(a) - keysOrder.indexOf(b)
        else if (aKey) -1
        else if (bKey) 1
        else a.compareTo(b)
      }
      .map(key => key -> obj.get(key))

  private def renderString(s: String): String = {
    // this can quote unnecessarily as long as it never fails to quote when necessary
    if (s.isEmpty)
      return ConfigUtil.quoteString(s)

    // if it starts with a hyphen or number, we have to quote
    // to ensure we end up with a string and not a number
    val first = s.codePointAt(0)
    if (Character.isDigit(first) || first == '-')
      return ConfigUtil.quoteString(s)

    if (s.startsWith("include") || s.startsWith("true") || s.startsWith("false") ||
        s.startsWith("null") || s.contains("//"))
      return ConfigUtil.quoteString(s)

    // only unquote if it's pure alphanumeric
    if (s.forall(c => c == '-' || Character.isLetter(c) || Character.isDigit(c))) s
    else ConfigUtil.quoteString(s)
  }

  private def parseable(f: File) = Parseable.newFile(f, ConfigParseOptions.defaults())
  private val parseValueMeth = ensureAccessible(classOf[Parseable].getDeclaredMethod("parseValue"))
  private def parseValue(p: Parseable) = parseValueMeth.invoke(p).asInstanceOf[ConfigValue]

  private def withEol(s: String) = if (s.takeRight(EOL.length) == EOL) s else s"$s$EOL"
}

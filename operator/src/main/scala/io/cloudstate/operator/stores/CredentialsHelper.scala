package io.cloudstate.operator.stores

import play.api.libs.json._
import play.api.libs.functional.syntax._
import skuber.EnvVar

/**
 * Credentials can either be supplied from a secret, or direct as values. The supported ways of configuring a
 * credential called username are:
 *
 * {{{
 * credentials:
 *   username: value
 *
 * credentialsFromSecret:
 *   name: secretname
 *   usernameKey: mykey (defaults to username)
 * }}}
 *
 * For a given key, only one of the above methods are allowed, but multiple methods can be combined for different
 * keys. So credentials can be pulled from multiple secrets, for example.
 */
object CredentialsHelper {

  def readCredentialParam(key: String): Reads[CredentialParam] =
    readCredentialParam(key, true).flatMap {
      case Some(param) => Reads.pure(param)
      case None =>
        Reads.failed(
          s"No $key specified in credentials. Either specify using credentials/$key, or by configuring credentialsFromSecret"
        )
    }

  def readOptionalCredentialParam(key: String): Reads[Option[CredentialParam]] =
    readCredentialParam(key, false)

  private def readCredentialParam(key: String, mandatory: Boolean): Reads[Option[CredentialParam]] = {

    def readFromCredentials[T](reads: Reads[Option[T]]) =
      (__ \ "credentials").readNullable(reads).map(_.flatten)

    val nameValueReads = readFromCredentials((__ \ key).readNullable[String].map(_.map(Value)))
    val credentialsFromSecretReads = (__ \ "credentialsFromSecret").readNullable(
      ((__ \ "name").read[String] and (__ \ (key + "Key")).readNullable[String]).tupled
    )

    (nameValueReads and credentialsFromSecretReads).tupled.flatMap {
      case (Some(_), Some((_, Some(_)))) =>
        Reads.failed(
          s"$key can either be specified as a value in credentials, or configured as ${key}Key in credentialsFromSecret, not both."
        )
      case (Some(value), _) => Reads.pure(Some(value))
      case (_, Some((name, Some(customKey)))) => Reads.pure(Some(FromSecret(name, customKey)))
      case (_, Some((name, None))) if mandatory => Reads.pure(Some(FromSecret(name, key)))
      case _ => Reads.pure(None)
    }
  }

  sealed trait CredentialParam {
    def toEnvVar: EnvVar.Value
  }
  case class FromSecret(name: String, key: String) extends CredentialParam {
    override def toEnvVar: EnvVar.Value = EnvVar.SecretKeyRef(key, name)
  }
  case class Value(value: String) extends CredentialParam {
    override def toEnvVar: EnvVar.Value = EnvVar.StringValue(value)
  }

}

package io.cloudstate.operator.stores
import io.cloudstate.operator.StatefulStore.Resource
import io.cloudstate.operator.{Condition, ImageConfig, OperatorConstants, StatefulStore, Validated}
import play.api.libs.json.JsValue
import skuber.EnvVar
import skuber.api.client.KubernetesClient

object InMemoryStoreSupport
    extends StatefulStoreSupport
    with ConfiguredStatefulStore
    with StatefulStoreUsageConfiguration {
  override def name: String = OperatorConstants.InMemoryStatefulStoreType
  override def validate(store: StatefulStore.Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] =
    Validated(this)
  override def reconcile(store: Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] =
    Validated(this)
  override def successfulConditions: List[Condition] = List()
  override def validateInstance(config: Option[JsValue],
                                client: KubernetesClient): Validated[StatefulStoreUsageConfiguration] = Validated(this)
  override def proxyImage(config: ImageConfig): String = config.inMemory
  override def proxyContainerEnvVars: List[EnvVar] = Nil
}

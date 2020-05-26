package io.cloudstate.proxy

import io.cloudstate.proxy.test.crud.ShoppingCartCrudTest.{AddLineItem, ShoppingCart}
import org.scalatest.{Matchers, WordSpecLike}

class EntityMethodDescriptorSpec extends WordSpecLike with Matchers {

  private val addItemDescriptor = ShoppingCart.descriptor
    .findServiceByName("ShoppingCart")
    .findMethodByName("AddItem")

  private val entityMethodDescriptor = new EntityMethodDescriptor(addItemDescriptor)

  "The EntityMethodDescriptor" should {

    "extract entity key" in {
      val subEntityKey =
        entityMethodDescriptor.extractId(
          AddLineItem("cartId", "userId", "productId", "name").toByteString
        )
      subEntityKey should ===("cartId")
    }

    "extract crud sub entity key" in {
      val subEntityKey =
        entityMethodDescriptor.extractCrudSubEntityId(
          AddLineItem("cartId", "userId", "productId", "name").toByteString
        )
      subEntityKey should ===("userId")
    }

    "extract crud command type" in {
      val commandType = entityMethodDescriptor.extractCrudCommandType(
        AddLineItem("cartId", "userId", "create", "productId", "name").toByteString
      )
      commandType should ===("create")
    }

  }
}

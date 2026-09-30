package com.avoqado.escritorio

import java.nio.file.Path

/** Un segundo proceso de verdad: toma el candado de la carpeta dada, avisa y se queda vivo. */
fun main(args: Array<String>) {
    println(if (CandadoDeInstancia.tomar(Path.of(args[0]))) "TOMADO" else "OCUPADO")
    System.out.flush()
    Thread.sleep(60_000)
}

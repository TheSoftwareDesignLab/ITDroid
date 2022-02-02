import React, { useEffect, useRef } from 'react'

const Canvas = ({
  principal,
  relations,
  imgSrc,
  resizePortion,
  paintRelations,
  ...props
}) => {
  const canvasRef = useRef(null)

  useEffect(() => {
    const canvas = canvasRef.current
    const context = canvas.getContext('2d')

    const image = new Image()
    image.src = imgSrc
    image.onload = () => {
      context.drawImage(
        image,
        0,
        0,
        context.canvas.width,
        context.canvas.height
      )

      if (principal) {
        context.lineWidth = 2
        context.strokeStyle = 'green'

        const originPrincipalX = principal.origin[0] * resizePortion
        const originPrincipalY = principal.origin[1] * resizePortion
        const widthPrincipal =
          principal.ending[0] * resizePortion - originPrincipalX
        const heightPrincipal =
          principal.ending[1] * resizePortion - originPrincipalY

        context.strokeRect(
          originPrincipalX,
          originPrincipalY,
          widthPrincipal,
          heightPrincipal
        )
        if (paintRelations && relations) {
          context.strokeStyle = 'orange'

          for (const relation of relations) {
            const originX = relation.origin[0] * resizePortion
            const originY = relation.origin[1] * resizePortion
            const width = relation.ending[0] * resizePortion - originX
            const height = relation.ending[1] * resizePortion - originY

            context.strokeRect(originX, originY, width, height)
          }
        }
      }
    }
  }, [])

  return (
    <canvas
      ref={canvasRef}
      {...props}
      width={1440 * resizePortion}
      height={2560 * resizePortion}
    />
  )
}

export default Canvas

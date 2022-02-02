import rtlLanguages from '../constants/rtlLanguages'

/**
 * Paints the nodes when the scripts are the same
 * @param {*} defaultLangPath Default language path
 * @param {*} selectedLangPath Selected language path
 * @param {*} defaultLangNodes Default languages nodes
 * @param {*} selectedLangNodes Selected language path
 * @param {number} stateID State ID
 * @param {number} nodeID Node ID
 * @param {(defaultLangImage: string, selectedLangImage: string, stateID: number, nodeID: number) => void} callback
 */
export function paintNodes(
  defaultLangPath,
  selectedLangPath,
  defaultLangNodes,
  selectedLangNodes,
  stateID,
  nodeID,
  callback
) {
  const canvasDefaultLang = document.createElement('canvas')
  canvasDefaultLang.width = 1440 / 4
  canvasDefaultLang.height = 2560 / 4
  const contextDefaultLang = canvasDefaultLang.getContext('2d')

  const canvasSelectedLang = document.createElement('canvas')
  canvasSelectedLang.width = 1440 / 4
  canvasSelectedLang.height = 2560 / 4
  const contextSelectedLang = canvasSelectedLang.getContext('2d')

  let originX,
    originY,
    width,
    height = 0

  const imageDefaultLang = new Image()
  const imageSelectedLang = new Image()
  imageDefaultLang.src = defaultLangPath
  imageSelectedLang.src = selectedLangPath

  let imageDefaultLangPromise = new Promise(function (resolve) {
    imageDefaultLang.onload = () => {
      /*contextDefaultLang.drawImage(
        imageDefaultLang,
        0,
        0,
        contextDefaultLang.canvas.width,
        contextDefaultLang.canvas.height
      )*/
      contextDefaultLang.fillRect(
        0,
        0,
        contextDefaultLang.canvas.width,
        contextDefaultLang.canvas.height
      )

      for (const defaultLangNode of defaultLangNodes) {
        //if (defaultLangNode.node.xPath.includes('TextView')) {
        originX = defaultLangNode.node.origin[0] / 4
        originY = defaultLangNode.node.origin[1] / 4
        width = defaultLangNode.node.ending[0] / 4 - originX
        height = defaultLangNode.node.ending[1] / 4 - originY

        if (defaultLangNode.principal) {
          contextDefaultLang.fillStyle = 'green'
        } else {
          contextDefaultLang.fillStyle = 'orange'
        }
        contextDefaultLang.fillRect(originX, originY, width, height)
        //}
      }

      resolve()
    }
  })

  let imageSelectedLangPromise = new Promise(function (resolve) {
    imageSelectedLang.onload = () => {
      /*contextSelectedLang.drawImage(
        imageSelectedLang,
        0,
        0,
        contextSelectedLang.canvas.width,
        contextSelectedLang.canvas.height
      )*/
      contextSelectedLang.fillRect(
        0,
        0,
        contextSelectedLang.canvas.width,
        contextSelectedLang.canvas.height
      )

      for (const selectedLangNode of selectedLangNodes) {
        //if (selectedLangNode.node.xPath.includes('TextView')) {
        originX = selectedLangNode.node.origin[0] / 4
        originY = selectedLangNode.node.origin[1] / 4
        width = selectedLangNode.node.ending[0] / 4 - originX
        height = selectedLangNode.node.ending[1] / 4 - originY

        if (selectedLangNode.principal) {
          contextSelectedLang.fillStyle = 'green'
        } else {
          contextSelectedLang.fillStyle = 'orange'
        }

        contextSelectedLang.fillRect(originX, originY, width, height)
        //}
      }

      resolve()
    }
  })

  imageDefaultLangPromise.then(() => {
    imageSelectedLangPromise.then(() => {
      callback(
        canvasDefaultLang.toDataURL(),
        canvasSelectedLang.toDataURL(),
        stateID,
        nodeID
      )
    })
  })
}

/**
 * Paints the nodes when the script is different
 * @param {*} selectedLangPath Selected language path
 * @param {*} selectedLangNodes Selected language path
 * @param {number} stateID State ID
 * @param {number} nodeID Node ID
 * @param {(selectedLangImage: string, stateID: number, nodeID: number) => void} callback
 */
export function paintDifferentScriptNodes(
  selectedLangPath,
  selectedLangNode,
  stateID,
  nodeID,
  callback
) {
  const canvasSelectedLang = document.createElement('canvas')
  canvasSelectedLang.width = 1440 / 4
  canvasSelectedLang.height = 2560 / 4
  const contextSelectedLang = canvasSelectedLang.getContext('2d')

  let originX,
    originY,
    width,
    height,
    distanceRight = 0

  const imageSelectedLang = new Image()
  imageSelectedLang.src = selectedLangPath

  let imageSelectedLangPromise = new Promise(function (resolve) {
    imageSelectedLang.onload = () => {
      contextSelectedLang.drawImage(
        imageSelectedLang,
        0,
        0,
        contextSelectedLang.canvas.width,
        contextSelectedLang.canvas.height
      )

      originX = selectedLangNode.node.origin[0] / 4
      originY = selectedLangNode.node.origin[1] / 4
      width = selectedLangNode.node.ending[0] / 4 - originX
      height = selectedLangNode.node.ending[1] / 4 - originY

      contextSelectedLang.fillStyle = 'green'
      contextSelectedLang.globalAlpha = 0.75

      contextSelectedLang.fillRect(originX, originY, width, height)

      // Paint calculated position
      distanceRight = contextSelectedLang.canvas.width - (originX + width)

      contextSelectedLang.fillStyle = '#7d26bf'

      contextSelectedLang.fillRect(distanceRight, originY, width, height)

      resolve()
    }
  })

  imageSelectedLangPromise.then(() => {
    callback(canvasSelectedLang.toDataURL(), stateID, nodeID)
  })
}

/**
 * Arranges all of the nodes from the object in a single array and asigns the corresponding id to each one.
 * @param {*} data Data of the XML file as a JS object.
 * @returns The array of nodes sorted by their id.
 */
export function arrangeNodes(data) {
  const nodes = addNode(data.hierarchy.node)

  return nodes.nodes
}

/**
 * Recursively iterates over the nodes and adds them to the array with their respective id (generated by the algorithm) and attributes.
 * @param {*} node The node to add to the array
 * @param {*} id The current id
 * @param {*} nodes The list of nodes
 * @returns The list of nodes and the next id that will be used.
 */
function addNode(node, id = 0, nodes = []) {
  if (node._attributes) {
    nodes.push({ id: id, attributes: node._attributes })
    id += 1
  }

  if (Array.isArray(node)) {
    for (const element of node) {
      const info = addNode(element, id, nodes)
      nodes = info.nodes
      id = info.id
    }
  } else if (node.node) {
    const info = addNode(node.node, id, nodes)
    nodes = info.nodes
    id = info.id
  }

  return { nodes, id }
}

export function scriptIsDifferent(destLanguage, dfltLanguage) {
  const destIsRTL = rtlLanguages.includes(destLanguage)
  const dfltIsRTL = rtlLanguages.includes(dfltLanguage)

  return (!dfltIsRTL && destIsRTL) || (dfltIsRTL && !destIsRTL)
}

import _ from 'lodash'
import React, { useEffect, useState } from 'react'
import Table from 'react-bootstrap/Table'
import Badge from 'react-bootstrap/Badge'
import Resemble from 'resemblejs'
import { languageName } from '../../constants/enums'
import {
  paintNodes,
  paintDifferentScriptNodes,
  scriptIsDifferent,
} from '../../shared/utilityFunctions'
import Canvas from '../canvas/Canvas'
import IpfDetail from '../IpfDetail/IpfDetail'

const ComparingTable = ({
  report,
  graphs,
  paths,
  lang,
  defaultLanguage,
  getLanguagePath,
}) => {
  const [imageDataUrls, setImageDataUrls] = useState([])
  const [missingStateImage, setMissingStateImage] = useState([])

  const getNode = (language, stateID, nodeID) => {
    const graph = graphs.find((el) => el.language === language)
    const node = graph.states[stateID - 1].nodes[nodeID]

    return {
      origin: [node.point1.x, node.point1.y],
      ending: [node.point2.x, node.point2.y],
      xPath: node.xPath,
    }
  }

  const getRelationNodes = (language, stateID, relations) => {
    let relationNodes = []

    for (const relation of relations) {
      relationNodes.push(getNode(language, stateID, relation.relNode))
    }

    return relationNodes
  }

  const getRelations = (language, stateID, nodeID) => {
    const ipfs = report.langsReport[language].ipfs
    return ipfs.find((ipf) => ipf.stateID === stateID && ipf.nodeID === nodeID)
      .relations
  }

  const getMissingStates = () => {
    let missingStatesIDs = []
    const totalStates = report.langsReport[defaultLanguage].amStates
    missingStatesIDs = _.range(1, totalStates + 1)

    if (report.langsReport[lang.key].ipfs) {
      for (const ipf of report.langsReport[lang.key].ipfs) {
        missingStatesIDs = missingStatesIDs.filter((stateID) => {
          return stateID !== ipf.stateID
        })
      }
    }

    return missingStatesIDs
  }

  const getImageDataUrl = (stateID, nodeID, isMissing = false) => {
    if (isMissing) {
      return missingStateImage.find((el) => el.stateID === stateID).dataUrl
    } else {
      return imageDataUrls.find(
        (el) => el.stateID === stateID && el.nodeID === nodeID
      ).dataUrl
    }
  }

  const isMissingMirroring = (relations) => {
    return relations.some((relation) => {
      const missing = relation.missing ?? ''
      return missing.length !== 0
    })
  }

  useEffect(() => {
    const ipfs = report.langsReport[lang.key].ipfs
    let stateID, nodeID, relations, defaultLangNodes, selectedLangNodes

    if (ipfs) {
      for (const ipf of ipfs) {
        stateID = ipf.stateID
        nodeID = ipf.nodeID
        relations = ipf.relations
        defaultLangNodes = []
        selectedLangNodes = []

        defaultLangNodes.push({
          node: getNode(defaultLanguage, stateID, nodeID),
          principal: true,
        })
        selectedLangNodes.push({
          node: getNode(lang.key, stateID, nodeID),
          principal: true,
        })

        for (const relation of relations) {
          defaultLangNodes.push({
            node: getNode(defaultLanguage, stateID, relation.relNode),
            principal: false,
          })
          selectedLangNodes.push({
            node: getNode(lang.key, stateID, relation.relNode),
            principal: false,
          })
        }

        if (scriptIsDifferent(lang.key, defaultLanguage)) {
          paintDifferentScriptNodes(
            `${getLanguagePath(lang.key)}${stateID}.png`,
            selectedLangNodes.find((node) => node.principal),
            stateID,
            nodeID,
            (selectedLangImage, stateIDParam, nodeIDParam) => {
              setImageDataUrls((prevUrls) => [
                ...prevUrls,
                {
                  stateID: stateIDParam,
                  nodeID: nodeIDParam,
                  dataUrl: selectedLangImage,
                },
              ])
            }
          )
        } else {
          paintNodes(
            `${getLanguagePath(defaultLanguage)}${stateID}.png`,
            `${getLanguagePath(lang.key)}${stateID}.png`,
            defaultLangNodes,
            selectedLangNodes,
            stateID,
            nodeID,
            (
              defaultLangImage,
              selectedLangImage,
              stateIDParam,
              nodeIDParam
            ) => {
              Resemble(defaultLangImage)
                .compareTo(selectedLangImage)
                .onComplete((data) => {
                  setImageDataUrls((prevUrls) => [
                    ...prevUrls,
                    {
                      stateID: stateIDParam,
                      nodeID: nodeIDParam,
                      dataUrl: data.getImageDataUrl(),
                    },
                  ])
                })
            }
          )
        }
      }
    }

    for (const missingStateID of getMissingStates()) {
      Resemble(`${getLanguagePath(defaultLanguage)}${missingStateID}.png`)
        .compareTo(`${getLanguagePath(lang.key)}${missingStateID}.png`)
        .onComplete((data) => {
          setMissingStateImage((prevUrls) => [
            ...prevUrls,
            { stateID: missingStateID, dataUrl: data.getImageDataUrl() },
          ])
        })
    }
  }, [])

  return (
    <div>
      <Table striped bordered hover className="text-center">
        <thead>
          <tr>
            <th>State</th>
            <th>Node ID</th>
            <th>{`${languageName[defaultLanguage]} Version`}</th>
            <th>{`${languageName[lang.key]} Version`}</th>
            <th>
              {scriptIsDifferent(lang.key, defaultLanguage)
                ? 'Estimated Position'
                : 'ResembleJS'}
            </th>
          </tr>
        </thead>
        <tbody>
          {report.langsReport[lang.key].ipfs ? (
            _.range(1, report.langsReport[defaultLanguage].amStates + 1).map(
              (stateID) => {
                return report.langsReport[lang.key].ipfs
                  .filter((ipf) => ipf.stateID === stateID)
                  .sort((ipfA, ipfB) => ipfA.nodeID - ipfB.nodeID)
                  .map((ipf, i, ipfArray) => {
                    if (i === 0) {
                      return (
                        <tr key={i}>
                          <td rowSpan={ipfArray.length}>{stateID}</td>
                          <td>
                            <p>{ipf.nodeID}</p>
                            {isMissingMirroring(ipf.relations) && (
                              <Badge pill variant="danger" className="mb-2">
                                Missing mirroring
                              </Badge>
                            )}
                            <IpfDetail
                              ipf={ipf}
                              destLanguage={lang.key}
                              dfltLanguage={defaultLanguage}
                            />
                          </td>
                          <td>
                            {graphs.find(
                              (el) => el.language === defaultLanguage
                            ) && (
                              <Canvas
                                principal={getNode(
                                  defaultLanguage,
                                  stateID,
                                  ipf.nodeID
                                )}
                                relations={getRelationNodes(
                                  defaultLanguage,
                                  stateID,
                                  getRelations(lang.key, stateID, ipf.nodeID)
                                )}
                                imgSrc={`${getLanguagePath(
                                  defaultLanguage
                                )}${stateID}.png`}
                                resizePortion={0.2}
                                paintRelations={
                                  !scriptIsDifferent(lang.key, defaultLanguage)
                                }
                              />
                            )}
                          </td>
                          <td>
                            {graphs.find((el) => el.language === lang.key) && (
                              <Canvas
                                principal={getNode(
                                  lang.key,
                                  stateID,
                                  ipf.nodeID
                                )}
                                relations={getRelationNodes(
                                  lang.key,
                                  stateID,
                                  getRelations(lang.key, stateID, ipf.nodeID)
                                )}
                                imgSrc={`${getLanguagePath(
                                  lang.key
                                )}${stateID}.png`}
                                resizePortion={0.2}
                                paintRelations={
                                  !scriptIsDifferent(lang.key, defaultLanguage)
                                }
                              />
                            )}
                          </td>
                          <td>
                            {imageDataUrls.find(
                              (image) =>
                                image.stateID === stateID &&
                                image.nodeID === ipf.nodeID
                            ) && (
                              <Canvas
                                imgSrc={getImageDataUrl(stateID, ipf.nodeID)}
                                resizePortion={0.2}
                              />
                            )}
                          </td>
                        </tr>
                      )
                    } else {
                      return (
                        <tr key={i}>
                          <td>
                            <p>{ipf.nodeID}</p>
                            {isMissingMirroring(ipf.relations) && (
                              <Badge pill variant="danger" className="mb-2">
                                Missing mirroring
                              </Badge>
                            )}
                            <IpfDetail
                              ipf={ipf}
                              destLanguage={lang.key}
                              dfltLanguage={defaultLanguage}
                            />
                          </td>
                          <td>
                            {graphs.find(
                              (el) => el.language === defaultLanguage
                            ) && (
                              <Canvas
                                principal={getNode(
                                  defaultLanguage,
                                  stateID,
                                  ipf.nodeID
                                )}
                                relations={getRelationNodes(
                                  defaultLanguage,
                                  stateID,
                                  getRelations(lang.key, stateID, ipf.nodeID)
                                )}
                                imgSrc={`${getLanguagePath(
                                  defaultLanguage
                                )}${stateID}.png`}
                                resizePortion={0.2}
                                paintRelations={
                                  !scriptIsDifferent(lang.key, defaultLanguage)
                                }
                              />
                            )}
                          </td>
                          <td>
                            {graphs.find((el) => el.language === lang.key) && (
                              <Canvas
                                principal={getNode(
                                  lang.key,
                                  stateID,
                                  ipf.nodeID
                                )}
                                relations={getRelationNodes(
                                  lang.key,
                                  stateID,
                                  getRelations(lang.key, stateID, ipf.nodeID)
                                )}
                                imgSrc={`${getLanguagePath(
                                  lang.key
                                )}${stateID}.png`}
                                resizePortion={0.2}
                                paintRelations={
                                  !scriptIsDifferent(lang.key, defaultLanguage)
                                }
                              />
                            )}
                          </td>
                          <td>
                            {imageDataUrls.find(
                              (image) =>
                                image.stateID === stateID &&
                                image.nodeID === ipf.nodeID
                            ) && (
                              <Canvas
                                imgSrc={getImageDataUrl(stateID, ipf.nodeID)}
                                resizePortion={0.2}
                              />
                            )}
                          </td>
                        </tr>
                      )
                    }
                  })
              }
            )
          ) : (
            <tr>
              <td colSpan={5}>
                <strong>No IPFs detected</strong>
              </td>
            </tr>
          )}
        </tbody>
      </Table>

      {getMissingStates().length > 0 && (
        <div>
          <h4>Remaining States Without IPFs</h4>
          <Table striped bordered hover className="text-center">
            <thead>
              <tr>
                <th>State</th>
                <th>{`${languageName[defaultLanguage]} Version`}</th>
                <th>{`${languageName[lang.key]} Version`}</th>
                <th>ResembleJS</th>
              </tr>
            </thead>
            <tbody>
              {getMissingStates().map((stateID) => (
                <tr key={stateID}>
                  <td>{stateID}</td>
                  <td>
                    {getLanguagePath(defaultLanguage) && (
                      <Canvas
                        imgSrc={`${getLanguagePath(
                          defaultLanguage
                        )}${stateID}.png`}
                        resizePortion={0.25}
                      />
                    )}
                  </td>
                  <td>
                    {getLanguagePath(lang.key) && (
                      <Canvas
                        imgSrc={`${getLanguagePath(lang.key)}${stateID}.png`}
                        resizePortion={0.25}
                      />
                    )}
                  </td>
                  <td>
                    {missingStateImage.find(
                      (image) => image.stateID === stateID
                    ) && (
                      <Canvas
                        imgSrc={getImageDataUrl(stateID, null, true)}
                        resizePortion={0.25}
                      />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </div>
      )}
    </div>
  )
}

export default ComparingTable

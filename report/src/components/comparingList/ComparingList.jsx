import React, { useEffect, useState } from 'react'
import Col from 'react-bootstrap/Col'
import Container from 'react-bootstrap/Container'
import Row from 'react-bootstrap/Row'
import { languageName } from '../../constants/enums'
import ComparingTable from './ComparingTable'
import './styles.css'

/*let convert = require('xml-js')

const ORIGIN = 'Origin'
const ENDING = 'Ending'*/

const ComparingList = ({ report }) => {
  const [graphs, setGraphs] = useState([])
  const [paths, setPaths] = useState([])
  const [selectedLanguages, setSelectedLanguages] = useState([])
  const [defaultLanguage, setDefaultLanguage] = useState(null)
  // const [nodes, setNodes] = useState([])

  /* const getBounds = (part, id) => {
    let bounds = nodes.find((el) => el.id === id).attributes.bounds
    let boundsParts = bounds.split(/[^(\d+,\d+)]/)
    boundsParts = boundsParts.filter((el) => el.length !== 0)

    return part === ORIGIN
      ? transformBoundsPartsToArray(boundsParts[0])
      : transformBoundsPartsToArray(boundsParts[1])
  }

  const transformBoundsPartsToArray = (boundsParts) => {
    const parts = boundsParts.split(',')

    return [parseInt(parts[0]), parseInt(parts[1])]
  }*/

  /*const getNodes = (language, stateID) => {
    const graph = graphs.find((el) => el.language === language)

    return graph.states[stateID - 1].nodes
  }*/

  const getLanguagePath = (language) => {
    return paths.find((el) => el.language === language).path
  }

  useEffect(() => {
    /*fetch('results/trnsResults/en/1.xml')
      .then((res) => res.text())
      .then((res) => {
        let options = { compact: true, ignoreComment: true }
        const result = convert.xml2js(res, options)
        console.log({ nodes: arrangeNodes(result) })

        setNodes(arrangeNodes(result))
      })*/
    //console.log(report)

    for (const [key, value] of Object.entries(report.langsReport)) {
      setSelectedLanguages((prevData) => [
        ...prevData,
        { key: key, dflt: value.dflt === undefined ? false : true },
      ])

      if (value.dflt) {
        setDefaultLanguage(key)
      }

      fetch(
        `./results/trnsResults/${key}/graph.json`
      )
        .then((res) => res.json())
        .then((res) => {
          setPaths((prevPaths) => [
            ...prevPaths,
            {
              language: key,
              path: `./results/trnsResults/${key}/`,
            },
          ])
          setGraphs((prevGraphs) => [...prevGraphs, res])
        })
        .catch((err) => {
          fetch(
            `./results/noTrnsResults/${key}/graph.json`
          )
            .then((res) => res.json())
            .then((res) => {
              setPaths((prevPaths) => [
                ...prevPaths,
                {
                  language: key,
                  path: `./results/noTrnsResults/${key}/`,
                },
              ])
              setGraphs((prevGraphs) => [...prevGraphs, res])
            })
            .catch((err) => console.error(err))
        })
    }
  }, [])

  return (
    <Container fluid>
      <h2>Test Results</h2>

      <div
        className="legend-container shadow p-3 mb-3 sticky-top"
        style={{ backgroundColor: 'white' }}
      >
        <h3 className="text-center mb-2">Legend</h3>
        <Row>
          <Col className="text-center">
            <div id="principal-node-legend" className="legend-item"></div>
            <strong>Reference node</strong>
          </Col>
          <Col className="text-center">
            <div id="relation-node-legend" className="legend-item"></div>
            <strong>Relation node</strong>
          </Col>
          <Col className="text-center">
            <div id="difference-legend" className="legend-item"></div>
            <strong>Difference</strong>
          </Col>
          <Col className="text-center">
            <div id="calculated-position-legend" className="legend-item"></div>
            <strong>Estimated position (different script)</strong>
          </Col>
        </Row>
      </div>

      {selectedLanguages.map(
        (lang) =>
          !lang.dflt && (
            <div key={lang.key}>
              <h3>{languageName[lang.key]}</h3>

              {graphs.find((graph) => graph.language === lang.key) &&
                graphs.find((graph) => graph.language === defaultLanguage) && (
                  <div className="mb-5">
                    <ComparingTable
                      getLanguagePath={getLanguagePath}
                      graphs={graphs}
                      report={report}
                      paths={paths}
                      lang={lang}
                      defaultLanguage={defaultLanguage}
                    />
                    <hr />
                  </div>
                )}
            </div>
          )
      )}
    </Container>
  )
}

export default ComparingList
